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
                         String errorCode, int revision, String snapshotHash, boolean isCurrent, String staleReason,
                         String runId, Reanalysis reanalysis) {}
    /** A newer run in progress while the previous finished result stays visible. */
    public record Reanalysis(String runId, String requestedAt) {}
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

    private record Accepted(Result result, Long newRun) {}

    public Result retry(CallerContext caller, long project, long proposal, String key) {
        var accepted=tx.execute(ignored -> {
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
                if (replay.isPresent()) return new Accepted(replay.get(),null);
            }
            var snapshot=contexts.load(caller,project,proposal);
            runs.expire(project,proposal);
            if (runs.running(proposal,snapshot.proposal().revisionNo())) throw conflict("analysis_running");
            // Until a worker starts it, the run may wait behind others; the lease is shortened to leaseSeconds at start.
            long id=runs.insert(snapshot,rules.calculate(snapshot),caller.accountId(),leaseSeconds*ImpactRunExecutor.WAIT_ROUNDS);
            Result result=result(runs.run(id),true,null,null);
            if (key!=null) commands.save(project,caller.accountId(),operation,key,input,result);
            return new Accepted(result,id);
        });
        // Dispatch after the transaction ends: a rejected run is closed in a new transaction, which must not
        // be requested while this request still holds its own connection.
        if (accepted.newRun()!=null && !executor.submit(() -> execute(caller,project,proposal,accepted.newRun())))
            closeFailure(project,accepted.newRun(),"analysis_capacity","NOT_STARTED",List.of(),null);
        return accepted.result();
    }

    public Result get(CallerContext caller, long project, long proposal) { return read(caller,project,proposal,true); }

    /** Same as get without the lease-expiry write, for read-only transactions such as the approval preview. */
    public Result peek(CallerContext caller, long project, long proposal) { return read(caller,project,proposal,false); }

    private Result read(CallerContext caller, long project, long proposal, boolean expire) {
        // Reads and the conditional expire need no project lock. Holding that lock while a second
        // connection is borrowed for the freshness read could exhaust the pool under polling.
        var state=tx.execute(ignored -> {
            access.requireMember(caller,project);
            if (projects.findCurrentRole(project,caller.accountId()).isEmpty()) throw AccessErrors.notFound("impact_membership_changed");
            var current=runs.proposal(project,proposal);
            if (expire) runs.expire(project,proposal);
            int revision=((Number)current.get("revision_no")).intValue();
            var latest=runs.latest(project,proposal,revision);
            var pending=latest.isPresent() && "RUNNING".equals(latest.get().get("status")) ? Optional.<Map<String,Object>>empty() : runs.pending(proposal,revision);
            return new Read(revision,current.get("snapshot_hash").toString(),latest,pending);
        });
        if (state.latest().isEmpty()) return new Result(List.of(),List.of(),"NOT_EVALUATED",null,"analysis_not_started",
                state.revision(),state.snapshotHash(),true,null,null,reanalysis(state));
        var run=state.latest().get();
        String stale=null;
        if (((Number)run.get("revision_no")).intValue()!=state.revision()) stale="proposal_revision_changed";
        else if ("STALE".equals(run.get("status"))) stale="analysis_input_changed";
        else {
            try {
                // No transaction is held here; load opens its own single-connection snapshot.
                if (!run.get("input_fingerprint").equals(ImpactFingerprint.of(contexts.load(caller,project,proposal)))) stale="analysis_input_changed";
            } catch (ApiException changed) {
                if (changed.getStatus().value()!=409) throw changed;
                stale=changed.getCode();
            }
        }
        return result(run,stale==null,stale,reanalysis(state));
    }

    private record Read(int revision,String snapshotHash,Optional<Map<String,Object>> latest,Optional<Map<String,Object>> pending) {}

    private static Reanalysis reanalysis(Read state) {
        return state.pending().map(row -> new Reanalysis(row.get("id").toString(),time(row.get("requested_at")))).orElse(null);
    }

    private void execute(CallerContext caller, long project, long proposal, long id) {
        // 모델 응답에서 이미 받은 사용량과 model. 결과를 저장하지 못하고 실패로 닫는 경로에서도 비용 집계(llm_usage)에 남긴다 (-169)
        List<?> received=List.of();
        String model=null;
        try {
            boolean active=Boolean.TRUE.equals(tx.execute(ignored -> {
                lock(project); runs.expire(project,proposal);

                return runs.started(id,leaseSeconds);
            }));
            if (!active) return;
            var outcome=coordinator.analyze(caller,project,proposal,"impact-"+id);
            received=outcome.usage();
            model=outcome.model();
            final String responseModel=model;
            tx.executeWithoutResult(ignored -> {
                lock(project);
                var run=runs.run(id);
                boolean same=run.get("input_fingerprint").equals(ImpactFingerprint.of(outcome.input()));
                // Read on this connection while holding the lock: load does not mark the transaction rollback-only
                // for stale-input errors, so no second connection is needed.
                try { same &= run.get("input_fingerprint").equals(ImpactFingerprint.of(contexts.load(caller,project,proposal))); }
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
                // 비용 집계(llm_usage)는 실행 행에 넣을 수 있는지와 상관없이 받은 사용량 그대로 (-169). 이 트랜잭션이 되돌려지면 아래 실패 처리가 다시 적는다
                runs.recordUsage(id,outcome.usage(),responseModel);
                runs.finish(id,status,code,ai,generation,usage,usageStatus);
            });
        } catch (ApiException changed) {
            // 모델 응답 뒤 권한 재확인이 거절한 경우(AccessRecheckException)에는 결과는 버리되 받은 사용량·model 이 실려 온다
            if (received.isEmpty()) { received=ImpactAnalysisCoordinator.receivedUsage(changed); model=ImpactAnalysisCoordinator.receivedModel(changed); }
            if (changed.getStatus().value()==409 || changed.getStatus().value()==404)
                closeFailure(project,id,"analysis_input_changed","NOT_STARTED",received,model);
            else closeFailure(project,id,"analysis_interrupted","UNKNOWN",received,model);
        } catch (RuntimeException failure) {
            // Store only a bounded code; never persist provider exceptions or document contents as errors.
            // 모델 응답 뒤 예상하지 못한 오류(ReceivedUsageException)에도 받은 사용량·model 이 실려 온다
            if (received.isEmpty()) { received=ImpactAnalysisCoordinator.receivedUsage(failure); model=ImpactAnalysisCoordinator.receivedModel(failure); }
            closeFailure(project,id,"invalid_output","UNKNOWN",received,model);
        }
    }

    /**
     * 결과 없이 실행을 닫는다. 모델 응답에서 이미 받은 사용량이 있으면 실행 기록과 공통 사용량 표(llm_usage)에 남긴다 (-169) —
     * 권한 재확인 실패·결과 저장 실패처럼 결과를 버려도 실제 호출 비용이다. 프로젝트를 잠글 수 없으면(삭제 등) 실행은 그대로 두고
     * 사용량만 남긴 뒤 원래처럼 not_found 로 끝낸다 (실행은 리스가 끝나면 expire 가 닫는다)
     */
    private void closeFailure(long project,long id,String code,String usageStatus,List<?> received,String model) {
        // Runs outside any request transaction, so a fresh transaction is started here.
        var fresh=new TransactionTemplate(tx.getTransactionManager());
        fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        boolean locked=Boolean.TRUE.equals(fresh.execute(ignored -> {
            boolean active=projects.lockActiveProject(project);
            runs.recordUsage(id,received,model);
            if (!active) return false;
            Object stored=received;
            String storedStatus=received.isEmpty() ? usageStatus : "REPORTED";
            try { canonical(stored); }
            catch (ApiException oversized) { stored=List.of(); storedStatus="UNKNOWN"; }
            runs.finish(id,"analysis_input_changed".equals(code) ? "STALE" : "FAILED",code,null,null,stored,storedStatus);
            return true;
        }));
        if (!locked) throw AccessErrors.notFound("impact_project_missing");
    }



    private void lock(long project) {
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("impact_project_missing");
    }

    static Map<String,Object> generation(Map<String,Object> envelope,List<?> usage) {
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

    private static Result result(Map<String,Object> run,boolean current,String stale,Reanalysis reanalysis) {
        Object raw=run.get("ai_result");
        Object ai=raw==null ? null : JSON.readValue(raw.toString(),Object.class);
        List<?> candidates=ai instanceof Map<?,?> map && map.get("candidates") instanceof List<?> list ? list : List.of();
        Object gen=run.get("generation");
        return new Result(JSON.readValue(run.get("explicit_result").toString(),List.class),candidates,run.get("status").toString(),
                gen==null ? null : JSON.readValue(gen.toString(),Object.class),(String)run.get("error_code"),
                ((Number)run.get("revision_no")).intValue(),run.get("snapshot_hash").toString(),current,stale,
                run.get("id").toString(),reanalysis);
    }
}
