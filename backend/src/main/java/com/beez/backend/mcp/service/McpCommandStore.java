package com.beez.backend.mcp.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.CanonicalJson;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 변경 도구의 같은 키 재시도 처리 (공통 5절, S15P31A108-166 리뷰 반영).
 *
 * <ul>
 *   <li>키 범위: 인증 사용자(계정)·프로젝트·동작. 같은 키·같은 입력이면 저장해 둔 결과를 그대로 돌려주고,
 *       같은 키·다른 입력이면 409 idempotency_conflict</li>
 *   <li>보존 24시간·저장 표 mcp_command (V8). 문서 업로드·설계 항목(document_http_command·design_http_command)과 같은 방식이며
 *       Q12 전체 결정 전의 범위 한정 결정이다. 만료 행은 같은 키가 다시 올 때 지운다</li>
 *   <li>같은 키가 동시에 오면 {@link #lock} 으로 한 줄로 세운다. 그래서 두 번째 요청은 첫 요청이 커밋한 결과를 본다</li>
 * </ul>
 * 호출하는 쪽은 한 트랜잭션 안에서 권한 확인 → {@link #replay} → 업무 처리 → {@link #save} 순서로 부른다.
 * 권한 확인을 먼저 하는 이유: 재요청도 현재 권한을 확인한다(공통 5절).
 */
@Component
public class McpCommandStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public McpCommandStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 저장된 결과가 있으면 돌려준다. 없으면 빈 값 — 호출자가 업무를 처리하고 {@link #save} 한다.
     * 이 키에 대한 트랜잭션 잠금을 먼저 잡는다 (트랜잭션이 끝나면 자동으로 풀린다).
     */
    public <T> Optional<T> replay(Long projectId, Long accountId, String operation, String key, Object input, Class<T> type) {
        lock(projectId, accountId, operation, key);
        jdbc.sql("""
                        delete from mcp_command
                         where project_id = :p and account_id = :a and operation = :o and request_key = :k
                           and expires_at <= now()
                        """)
                .param("p", projectId).param("a", accountId).param("o", operation).param("k", key)
                .update();
        Optional<Map<String, Object>> row = jdbc.sql("""
                        select fingerprint, response::text as response from mcp_command
                         where project_id = :p and account_id = :a and operation = :o and request_key = :k
                        """)
                .param("p", projectId).param("a", accountId).param("o", operation).param("k", key)
                .query().listOfRows().stream().findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        if (!fingerprint(input).equals(row.get().get("fingerprint"))) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
                    "같은 요청 키로 다른 내용이 전송되었습니다. 새 요청 키로 다시 시도해 주세요.");
        }
        // readValue: 저장한 JSON 을 원래 응답 타입으로 되돌린다
        return Optional.of(json.readValue(row.get().get("response").toString(), type));
    }

    /** 처리 결과를 저장한다. replay 와 같은 트랜잭션에서 부른다 */
    public void save(Long projectId, Long accountId, String operation, String key, Object input, Object response) {
        jdbc.sql("""
                        insert into mcp_command (project_id, account_id, operation, request_key, fingerprint, response)
                        values (:p, :a, :o, :k, :f, cast(:r as jsonb))
                        """)
                .param("p", projectId).param("a", accountId).param("o", operation).param("k", key)
                .param("f", fingerprint(input))
                // writeValueAsString: 응답 객체를 JSON 문자열로 바꾼다
                .param("r", json.writeValueAsString(response))
                .update();
    }

    /**
     * 같은 (프로젝트·계정·동작·키) 요청을 한 줄로 세운다. pg_advisory_xact_lock 은 PostgreSQL 이 제공하는
     * "이름 붙은 잠금"으로, 같은 번호를 잡으려는 다른 트랜잭션은 앞 트랜잭션이 끝날 때까지 기다린다.
     * hashtextextended 는 문자열을 그 잠금 번호(64비트 정수)로 바꾼다.
     */
    private void lock(Long projectId, Long accountId, String operation, String key) {
        String name = "mcp|" + projectId + "|" + accountId + "|" + operation + "|" + key;
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:n, 0))) x")
                .param("n", name)
                .query(Integer.class)
                .single();
    }

    /** 입력에서 요청 키를 뺀 나머지의 정규 JSON SHA-256 ({@link CanonicalJson}). 필드 순서만 다른 재요청은 같은 입력이다 */
    String fingerprint(Object input) {
        // convertValue: 객체를 Map·List 로 바꾼다 (이름은 응답과 같은 규칙)
        Object tree = json.convertValue(input, Object.class);
        if (tree instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), e.getValue());
            }
            copy.remove("idempotency_key");
            copy.remove("idempotencyKey");
            tree = copy;
        }
        return CanonicalJson.sha256(json, tree);
    }
}
