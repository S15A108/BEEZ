package com.beez.backend.mcp.server;

import com.beez.backend.common.access.AccessDeniedApiException;
import com.beez.backend.common.access.AgentPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 도구 호출 하나의 감사 기록 한 줄 (mcp_audit_log, MCP-09, -177). 값만 모으고 저장은 {@link McpAuditRecorder} 가 한다.
 *
 * <p><b>남기는 것</b>: 호출자(토큰·계정)·프로젝트·도구·대상 ID·결과·지연·실패 code·거부 사유 종류.
 * <b>남기지 않는 것</b>: 인수 본문(요청 문장·기록 원문·제안 내용), 토큰 값, 응답 본문 (02-mcp-tools "연결과 인증", Jira -177 작업 4).
 * 대상 ID 는 인수 중 이름이 {@code id}·{@code *_id}·{@code *_ids} 인 칸의 값만, 숫자 ID(십진 19자리 이하)일 때만 담는다.
 *
 * @param result     OK · DENIED({@link AccessDeniedApiException}) · ERROR(그 밖의 실패)
 * @param denyReason DENIED 일 때 거부 사유 종류(예: not_member, task_not_in_project). 그 밖은 null
 * @param errorCode  실패면 응답의 error.code, 성공이면 null
 */
record McpAuditEntry(Long agentTokenId, Long accountId, Long projectId, String tool, List<Map<String, String>> targetIds,
                     String result, String denyReason, String errorCode, int latencyMs) {

    static final String OK = "OK";
    static final String DENIED = "DENIED";
    static final String ERROR = "ERROR";

    /** 한 호출에서 남길 대상 ID 수 상한. 목록 인수(changed_design_item_ids 등)가 길어도 행이 커지지 않게 */
    static final int MAX_TARGETS = 50;

    /**
     * API ID 는 BIGINT 십진 문자열이다(00-common 3절, {@code Ids}). 숫자만 받는다 — 영숫자를 허용하면 base64url 토큰(43자)·키가
     * 모르는 {@code *_id} 칸에 담겨 들어올 때 그대로 감사 행에 남는다 (리뷰 반영)
     */
    private static final Pattern ID_VALUE = Pattern.compile("[0-9]{1,19}");

    /**
     * @param principal 토큰 주체. 정상 경로에서는 항상 있다(없으면 호출자 칸을 비운다)
     * @param arguments 도구 인수 원본(변환 전). 대상 ID 만 뽑는다
     * @param failure   실패를 만든 예외. 성공이거나 예외 없이 실패를 정했으면 null
     * @param errorCode 실패면 응답 code, 성공이면 null
     */
    static McpAuditEntry of(AgentPrincipal principal, String tool, Map<String, Object> arguments, Exception failure,
                            String errorCode, long latencyMs) {
        String result;
        String denyReason = null;
        if (errorCode == null) {
            result = OK;
        } else if (failure instanceof AccessDeniedApiException denied) {
            result = DENIED;
            denyReason = denied.reasonKind();
        } else {
            result = ERROR;
        }
        return new McpAuditEntry(
                principal == null ? null : principal.agentTokenId(),
                principal == null ? null : principal.accountId(),
                principal == null ? null : principal.projectId(),
                tool, targetIds(arguments), result, denyReason, errorCode,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs)));
    }

    /** 인수를 앞에서부터 훑어 ID 칸만 {"field","id"} 로 모은다. 중첩 객체·목록(plan.design_refs[] 등)도 본다 */
    static List<Map<String, String>> targetIds(Map<String, Object> arguments) {
        List<Map<String, String>> out = new ArrayList<>();
        if (arguments != null) {
            collect(arguments, out);
        }
        return out;
    }

    private static void collect(Object node, List<Map<String, String>> out) {
        if (out.size() >= MAX_TARGETS) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if (key.equals("id") || key.endsWith("_id")) {
                    add(key, value, out);
                } else if (key.endsWith("_ids") && value instanceof List<?> list) {
                    for (Object item : list) {
                        add(key, item, out);
                    }
                } else {
                    collect(value, out);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collect(item, out);
            }
        }
    }

    private static void add(String field, Object value, List<Map<String, String>> out) {
        if (out.size() >= MAX_TARGETS || !(value instanceof String || value instanceof Number)) {
            return;
        }
        String id = value.toString();
        if (ID_VALUE.matcher(id).matches()) {
            Map<String, String> target = new LinkedHashMap<>();
            target.put("field", field);
            target.put("id", id);
            out.add(target);
        }
    }
}
