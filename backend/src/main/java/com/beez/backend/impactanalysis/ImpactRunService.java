package com.beez.backend.impactanalysis;

import static com.beez.backend.designitem.support.DesignJson.*;
import com.beez.backend.common.access.*;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class ImpactRunService {
    public record Result(List<?> explicit, List<?> candidates, String analysisStatus, Object generation,
                         String errorCode, int revision, String snapshotHash, boolean isCurrent, String staleReason) {}
    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;
    private final DesignCommandService commands;
    private final ImpactContextQuery contexts;
    private final ExplicitImpactCalculator rules;
    private final ImpactAnalysisCoordinator coordinator;
    private final ImpactRunRepository runs;
    private final ImpactRunExecutor executor;
    private final TransactionTemplate tx;
    private final long leaseSeconds;

    public ImpactRunService(ProjectAccessChecker access, DesignProjectRepository projects, DesignCommandService commands,
            ImpactContextQuery contexts, ExplicitImpactCalculator rules, ImpactAnalysisCoordinator coordinator,
            ImpactRunRepository runs, ImpactRunExecutor executor, PlatformTransactionManager transactions, BeezProperties properties) {
        this.access=access; this.projects=projects; this.commands=commands; this.contexts=contexts; this.rules=rules;
        this.coordinator=coordinator; this.runs=runs; this.executor=executor; this.tx=new TransactionTemplate(transactions);
        this.leaseSeconds=Math.max(60, properties.ai().readTimeout().plus(properties.ai().connectTimeout()).toSeconds()+60);
    }

    public Result retry(CallerContext caller, long project, long proposal, String key) {
        return tx.execute(ignored -> {
            access.requireMember(caller, project).requireHuman();
            lock(project);
            // Recheck membership after waiting for a concurrent project command.
            if (projects.findCurrentRole(project, caller.accountId()).isEmpty()) throw AccessErrors.notFound("impact_membership_changed");
            var revision = runs.proposal(project, proposal);
            String operation="proposal:impact:"+proposal+":human";
            // Endpoint has no body. Binding to the submitted revision detects key reuse after resubmission.
            var input=Map.of("proposal",proposal,"revision",revision.get("revision_no"),"snapshot_hash",revision.get("snapshot_hash"));
            if (key!=null) {
                var replay=commands.replay(project,caller.accountId(),operation,key,input,Result.class);
                if (replay.isPresent()) return replay.get();
            }
            var snapshot=contexts.load(caller,project,proposal);
            runs.expire(project,proposal);
            if (runs.running(proposal,snapshot.proposal().revisionNo())) throw conflict("analysis_running");
            long id=runs.insert(snapshot,rules.calculate(snapshot),caller.accountId(),leaseSeconds);
            Result result=result(runs.run(id),true,null);
            if (key!=null) commands.save(project,caller.accountId(),operation,key,input,result);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    if (!executor.submit(() -> execute(caller,project,proposal,id)))
                        closeFailure(project,id,"analysis_capacity","NOT_STARTED");
                }
            });
            return result;
        });
    }

    public Result get(CallerContext caller, long project, long proposal) {
        return tx.execute(ignored -> {
            access.requireMember(caller,project);
            lock(project);
            access.requireMember(caller,project);
            if (projects.findCurrentRole(project,caller.accountId()).isEmpty()) throw AccessErrors.notFound("impact_membership_changed");
            var current=runs.proposal(project,proposal);
            runs.expire(project,proposal);
            int revision=((Number)current.get("revision_no")).intValue();
            var latest=runs.latest(project,proposal,revision);
            if (latest.isEmpty()) return new Result(List.of(),List.of(),"NOT_EVALUATED",null,"analysis_not_started",
                    revision,current.get("snapshot_hash").toString(),true,null);
            var run=latest.get();
            String stale=null;
            if (((Number)run.get("revision_no")).intValue()!=revision) stale="proposal_revision_changed";
            else if ("STALE".equals(run.get("status"))) stale="analysis_input_changed";
            else {
                try {
                    if (!run.get("input_fingerprint").equals(hash(loadCurrent(caller,project,proposal)))) stale="analysis_input_changed";
                } catch (ApiException changed) {
                    if (changed.getStatus().value()!=409) throw changed;
                    stale=changed.getCode();
                }
            }
            return result(run,stale==null,stale);
        });
    }

    private void execute(CallerContext caller, long project, long proposal, long id) {
        try {
            boolean active=Boolean.TRUE.equals(tx.execute(ignored -> {
                lock(project); runs.expire(project,proposal);
                if (!"RUNNING".equals(runs.run(id).get("status"))) return false;
                runs.started(id); return true;
            }));
            if (!active) return;
            var outcome=coordinator.analyze(caller,project,proposal,"impact-"+id);
            tx.executeWithoutResult(ignored -> {
                lock(project);
                var run=runs.run(id);
                boolean same=run.get("input_fingerprint").equals(hash(outcome.input()));
                try { same &= run.get("input_fingerprint").equals(hash(loadCurrent(caller,project,proposal))); }
                catch (ApiException changed) {
                    if (changed.getStatus().value()!=409 && changed.getStatus().value()!=404) throw changed;
                    same=false;
                }
                String status=!same ? "STALE" : switch(outcome.status()) {
                    case ANALYZED -> "COMPLETED";
                    case INSUFFICIENT_EVIDENCE -> outcome.usageStatus()==ImpactAnalysisCoordinator.UsageStatus.NOT_STARTED
                            ? "NOT_EVALUATED" : "INSUFFICIENT_EVIDENCE";
                    case AI_FAILED -> "FAILED";
                    case STALE -> "STALE";
                };
                var envelope=outcome.aiEnvelope();
                Object ai=null,generation=null;
                if (!"STALE".equals(status) && envelope!=null) {
                    ai=envelope.get("result");
                    generation=generation(envelope,outcome.usage());
                }
                String code="STALE".equals(status) ? "analysis_input_changed" : outcome.errorCode();
                Object usage=outcome.usage();
                String usageStatus=outcome.usageStatus().name();
                try { canonical(ai); canonical(generation); }
                catch (ApiException oversized) {
                    status="FAILED"; code="invalid_output"; ai=null; generation=null;
                }
                try { canonical(usage); }
                catch (ApiException oversized) {
                    usage=List.of(); usageStatus="UNKNOWN";
                    if (!"STALE".equals(status)) { status="FAILED"; code="invalid_output"; }
                }
                runs.finish(id,status,code,ai,generation,usage,usageStatus);
            });
        } catch (ApiException changed) {
            if (changed.getStatus().value()==409 || changed.getStatus().value()==404)
                closeFailure(project,id,"analysis_input_changed","NOT_STARTED");
            else closeFailure(project,id,"analysis_interrupted","UNKNOWN");
        } catch (RuntimeException failure) {
            // Store only a bounded code; never persist provider exceptions or document contents as errors.
            closeFailure(project,id,"invalid_output","UNKNOWN");
        }
    }

    private void closeFailure(long project,long id,String code,String usageStatus) {
        // afterCommit runs while the original connection may still be bound: force a new transaction.
        var fresh=new TransactionTemplate(tx.getTransactionManager());
        fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.executeWithoutResult(ignored -> { lock(project); runs.finish(id,
                "analysis_input_changed".equals(code) ? "STALE" : "FAILED",code,null,null,List.of(),usageStatus); });
    }

    private ImpactContextQuery.Snapshot loadCurrent(CallerContext caller,long project,long proposal) {
        // Isolate expected stale-input exceptions so they do not mark the result-write transaction rollback-only.
        // The outer transaction retains the project lock while this consistent read runs.
        var read=new TransactionTemplate(tx.getTransactionManager());
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        return read.execute(ignored -> contexts.load(caller,project,proposal));
    }

    private void lock(long project) {
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("impact_project_missing");
    }

    private static Map<String,Object> generation(Map<String,Object> envelope,List<?> usage) {
        var value=new LinkedHashMap<String,Object>();
        value.put("origin",envelope.getOrDefault("origin","MODEL"));
        if (envelope.get("model")!=null) value.put("model",envelope.get("model"));
        var analysis=(Map<?,?>)envelope.get("result");
        var candidates=(List<?>)analysis.get("candidates");
        var reasons=new ArrayList<String>();
        var sources=new LinkedHashSet<Object>();
        for (var candidate:candidates) {
            var map=(Map<?,?>)candidate;
            reasons.add((String)map.get("reason"));
            sources.addAll((List<?>)map.get("sources"));
        }
        // AI-A03 returns candidate reasons, not a separate narrative. Preserve those without inventing model prose.
        value.put("text",String.join("\n",reasons));
        value.put("sources",List.copyOf(sources));
        value.put("unresolved","INSUFFICIENT".equals(analysis.get("evidence_status")) ? List.of("insufficient_evidence") : List.of());
        value.put("usage",usage);
        return value;
    }

    private static Result result(Map<String,Object> run,boolean current,String stale) {
        Object raw=run.get("ai_result");
        Object ai=raw==null ? null : JSON.readValue(raw.toString(),Object.class);
        List<?> candidates=ai instanceof Map<?,?> map && map.get("candidates") instanceof List<?> list ? list : List.of();
        Object gen=run.get("generation");
        return new Result(JSON.readValue(run.get("explicit_result").toString(),List.class),candidates,run.get("status").toString(),
                gen==null ? null : JSON.readValue(gen.toString(),Object.class),(String)run.get("error_code"),
                ((Number)run.get("revision_no")).intValue(),run.get("snapshot_hash").toString(),current,stale);
    }
}
