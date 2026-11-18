package com.beez.backend.mcp.server;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * mcp_audit_log 에 한 줄 쓴다 (MCP-09, -177). {@link McpToolHandler} 만 부른다 — 도구·서비스는 감사 기록을 남기지 않는다(08 계약 1절).
 *
 * <p><b>트랜잭션.</b> 도구의 업무 트랜잭션은 서비스 메서드 안에서 이미 끝났다. 감사는 그와 따로 새 트랜잭션(REQUIRES_NEW)으로 써서,
 * 업무가 롤백된 실패 호출도 남고 감사 저장 실패가 업무 결과를 되돌리지 않는다. 저장 실패 처리는 호출하는 쪽(결과 유지 + 오류 로그).
 *
 * <p>context_access_log({@code ContextAccessRecorder})를 쓰지 않은 이유: 그쪽은 "어떤 버전을 봤나"를 업무와 같은 트랜잭션에 남기는 표라
 * 실패·거부 호출을 담을 수 없다. 이 표는 읽는 화면이 아직 없어 엔티티 없이 INSERT 한 줄만 둔다.
 */
@Component
class McpAuditRecorder {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    McpAuditRecorder(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(McpAuditEntry entry) {
        jdbc.sql("""
                insert into mcp_audit_log (agent_token_id, account_id, project_id, tool, target_ids, result,
                    deny_reason, error_code, latency_ms)
                values (:token, :account, :project, :tool, cast(:targets as jsonb), :result, :reason, :code, :latency)
                """)
                .param("token", entry.agentTokenId())
                .param("account", entry.accountId())
                .param("project", entry.projectId())
                .param("tool", entry.tool())
                .param("targets", json.writeValueAsString(entry.targetIds()))
                .param("result", entry.result())
                .param("reason", entry.denyReason())
                .param("code", entry.errorCode())
                .param("latency", entry.latencyMs())
                .update();
    }
}
