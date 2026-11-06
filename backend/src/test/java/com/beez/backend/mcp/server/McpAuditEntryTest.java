package com.beez.backend.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.auth.SecretTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 감사 행의 대상 ID 뽑기 (-177). 결과 분류는 McpToolHandlerTest, 실제 저장은 McpToolsIntegrationTests. */
class McpAuditEntryTest {

    /**
     * 깨지면: 감사 행에 요청 문장·기록 원문·멱등 키·토큰이 새거나(Jira -177 작업 4·완료 조건 "인증정보 미포함"),
     * 중첩 인수(plan.design_refs[]·*_ids 목록)의 대상 ID 가 빠져 "무엇에 접근했나"를 알 수 없다.
     */
    @Test
    void targetIds_keepOnlyNumericValuesOfIdFields() {
        String token = SecretTokens.generate();   // base64url 43자 — 영숫자 ID 모양이라 숫자 검사가 없으면 통과한다
        Map<String, Object> args = Map.of(
                "request", "로그인 화면 구현 — 비밀번호 hunter2",
                "idempotency_key", "k-1",
                "task_id", "12",
                "changed_design_item_ids", List.of("3", "4"),
                "plan", Map.of("title", "제목", "design_refs", List.of(Map.of("design_item_id", "7", "relation", "READS"))),
                "id", "write me a poem",   // ID 칸이라도 숫자 ID 가 아니면 남기지 않는다
                "token_id", token,         // 모르는 키로 거부될 호출이어도 원본 인수에서 뽑으므로 여기서 막는다
                "validation_id", 55);

        List<Map<String, String>> targets = McpAuditEntry.targetIds(args);

        assertThat(targets).containsExactlyInAnyOrder(
                Map.of("field", "task_id", "id", "12"),
                Map.of("field", "changed_design_item_ids", "id", "3"),
                Map.of("field", "changed_design_item_ids", "id", "4"),
                Map.of("field", "design_item_id", "id", "7"),
                Map.of("field", "validation_id", "id", "55"));
        assertThat(targets.toString()).doesNotContain("로그인", "hunter2", "k-1", "poem", token);
    }

    /** 깨지면: 아주 긴 ID 목록 인수 하나로 감사 행이 끝없이 커진다. */
    @Test
    void targetIds_areCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(String.valueOf(i));

        assertThat(McpAuditEntry.targetIds(Map.of("changed_design_item_ids", many))).hasSize(McpAuditEntry.MAX_TARGETS);
    }
}
