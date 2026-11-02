package com.beez.backend.changeproposal.service;

import static com.beez.backend.changeproposal.service.ProposalCommandService.error;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.ImpactStatusQuery;
import org.springframework.stereotype.Component;

/**
 * Approval gate (S15P31A108-241). A proposal is approved without a reason only when its current revision has a
 * completed, up-to-date impact analysis. Failed, insufficient, never-run and outdated analyses are never read as
 * "no impact"; the leader may still approve by leaving a reason. A running analysis cannot be overridden.
 */
@Component
public class ImpactApprovalGate {
    public enum Kind { TRUSTED, RUNNING, UNTRUSTED }
    public record Decision(Kind kind, String status, boolean isCurrent, String errorCode, int candidateCount,
                          boolean reanalysis) {
        public boolean needsReason() { return kind==Kind.UNTRUSTED; }
    }
    private final ImpactStatusQuery statuses;
    public ImpactApprovalGate(ImpactStatusQuery statuses) { this.statuses=statuses; }

    /** Call while holding the project lock, before any write; closes an expired RUNNING lease on the way. */
    public Decision evaluate(CallerContext caller, long project, long proposal) {
        return decide(statuses.currentFor(caller,project,proposal));
    }
    /** Read-only variant for the approval preview. */
    public Decision peek(CallerContext caller, long project, long proposal) {
        return decide(statuses.peekFor(caller,project,proposal));
    }
    static Decision decide(ImpactStatusQuery.Status status) {
        Kind kind;
        // A newer run in progress hides behind the previous finished result (reanalysis): it blocks like RUNNING.
        if ("RUNNING".equals(status.status()) || status.reanalysisRunning()) kind=Kind.RUNNING;
        else if ("COMPLETED".equals(status.status()) && status.isCurrent()) kind=Kind.TRUSTED;
        else kind=Kind.UNTRUSTED;
        return new Decision(kind,status.status(),status.isCurrent(),status.errorCode(),status.candidateCount(),status.reanalysisRunning());
    }

    /** Throws 409 unless approval may proceed; returns the reason to store with the approval, or null. */
    public String enforce(Decision decision, String overrideReason) {
        if (decision.kind()==Kind.RUNNING)
            throw error("analysis_running","영향 분석이 진행 중입니다. 완료된 뒤 다시 승인해 주세요.");
        if (decision.kind()==Kind.TRUSTED) return null;
        if (overrideReason==null)
            throw error("impact_not_confirmed","현재 제안의 영향 분석이 완료되지 않았습니다. 영향 분석을 실행하거나 사유를 남기고 승인해 주세요.");
        return overrideReason;
    }
}
