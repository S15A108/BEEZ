package com.beez.backend.agenttoken;

import com.beez.backend.account.Account;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.project.ProjectMemberRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AGT-A01 ~ A03 (S15P31A108-175). 발급은 사람 세션의 프로젝트 멤버, 목록·폐기는 본인 토큰만.
 * 토큰은 평문을 발급 응답에서 한 번만 주고 SHA-256 해시만 저장한다 ({@link SecretTokens}, NFR-03).
 * /mcp 요청의 토큰 검증은 {@link StoredAgentTokenVerifier}.
 */
@Service
public class AgentTokenService {

    static final int LABEL_MAX = 100;

    /** 발급 응답의 작업 절차 지침. MCP Tools 명세 "작업 호출 순서"를 줄였다 — 도구가 붙는 -178 에서 다시 맞춘다. */
    static final String CLAUDE_MD_SNIPPET = """
            ## BEEZ 작업 절차
            이 저장소의 작업은 BEEZ MCP 도구를 거친다.
            1. `get_development_context` 로 승인 설계와 근거를 읽는다.
            2. `upsert_task_plan` 으로 계획과 참조한 설계 버전을 저장한다.
            3. `validate_task_plan` 을 호출하고, 결과가 PASS 일 때만 다음으로 간다 (RUNNING 이면 `get_validation`).
            4. `report_task_state(START)` 로 시작을 알린다.
            5. 설계를 바꿔야 하면 직접 바꾸지 말고 `propose_design_change` 로 제안한다.
            6. 설계 변경이 발행되면 `get_design_changes` 로 확인하고, 계획을 고쳐 다시 검증한 뒤 `report_task_state(RESUME)` 로 이어 간다.
            7. 끝나면 결과와 계획 대비 변경을 `report_task_state(COMPLETED)` 로 보고한다.
            """;

    private final ProjectAccessChecker access;
    private final AgentTokenRepository tokens;
    private final ProjectMemberRepository members;
    private final AgentTokenProperties config;

    public AgentTokenService(ProjectAccessChecker access, AgentTokenRepository tokens, ProjectMemberRepository members,
                             AgentTokenProperties config) {
        this.access = access;
        this.tokens = tokens;
        this.members = members;
        this.config = config;
    }

    /**
     * AGT-A01. 멤버십 행을 잠근 뒤 한도를 센다 — 동시 발급이 한도를 넘지 않고, 제외(-212)와 겹쳐도 제외 뒤에 토큰이 생기지 않는다.
     * 프로젝트 삭제와는 줄을 서지 않는다: 삭제와 겹쳐 폐기를 피한 토큰이 생겨도 검증이 삭제된 프로젝트를 거부한다.
     */
    @Transactional
    public IssuedAgentTokenResponse issue(CallerContext caller, long projectId, CreateAgentTokenRequest req) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        String label = normalizeLabel(req == null ? null : req.label());

        // 잠근 뒤 활성 여부는 쿼리로 다시 본다. requireMember 가 같은 멤버십을 이미 읽어 두어서, 잠금 조회는 옛 상태의
        // 엔티티를 돌려준다 — 잠금을 기다리는 사이 커밋된 제외를 엔티티로는 못 본다 (InvitationRepository 와 같은 함정)
        members.findForUpdate(actor.memberId());
        if (!members.isActiveMember(actor.memberId(), projectId)) {
            throw AccessErrors.notFound("not_member project=" + projectId + " account=" + actor.accountId());
        }
        long active = tokens.countByProjectIdAndAccountIdAndRevokedAtIsNull(projectId, actor.accountId());
        if (active >= config.maxActiveTokens()) {
            throw new ApiException(HttpStatus.CONFLICT, "agent_limit_reached",
                    "이 프로젝트에서 내 활성 토큰은 " + config.maxActiveTokens() + "개까지입니다. 쓰지 않는 토큰을 폐기한 뒤 다시 발급하세요");
        }

        String raw = SecretTokens.generate();
        AgentToken token = tokens.save(AgentToken.issue(projectId, actor.accountId(), label, SecretTokens.hash(raw)));
        return new IssuedAgentTokenResponse(AgentTokenResponse.of(token), raw, config.mcpUrl(), CLAUDE_MD_SNIPPET);
    }

    /** AGT-A02. 내 토큰(폐기 포함), id 내림차순 커서. 프로젝트 필터는 쿼리 조건으로만 건다. */
    @Transactional(readOnly = true)
    public PageResponse<AgentTokenResponse> listMine(CallerContext caller, String projectIdRaw, String cursor,
                                                     Integer limit) {
        Account me = access.requireUser(caller);
        Long projectId = Ids.parse(projectIdRaw, "project_id");
        int size = PageResponse.normalizeLimit(limit);
        long beforeId = PageResponse.beforeId(cursor);
        PageRequest page = PageRequest.of(0, size + 1);
        List<AgentToken> rows;
        if (projectId == null) {
            rows = tokens.findMine(me.getId(), beforeId, page);
        } else {
            rows = tokens.findMineInProject(me.getId(), projectId, beforeId, page);
        }
        return PageResponse.fromRows(rows, size, AgentToken::getId, pageRows -> {
            List<AgentTokenResponse> items = new ArrayList<>();
            for (AgentToken t : pageRows) {
                items.add(AgentTokenResponse.of(t));
            }
            return items;
        });
    }

    /**
     * AGT-A03. 본인 토큰이면 프로젝트를 나간 뒤에도 폐기할 수 있다. 이미 폐기된 본인 토큰도 204, 남의 토큰·없는 토큰은 404.
     * 커밋 뒤의 /mcp 요청은 검증({@link StoredAgentTokenVerifier})과 권한 검사가 매번 폐기 여부를 읽어 바로 거부한다.
     */
    @Transactional
    public void revoke(CallerContext caller, long tokenId) {
        Account me = access.requireUser(caller);
        if (tokens.revoke(tokenId, me.getId(), Instant.now()) == 0
                && tokens.findByIdAndAccountId(tokenId, me.getId()).isEmpty()) {
            throw AccessErrors.notFound("agent_token_missing token=" + tokenId + " account=" + me.getId());
        }
    }

    /** 앞뒤 공백을 뺀 이름. 없거나 공백뿐이면 이름 없음(null), 길면 400. */
    private static String normalizeLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String label = raw.strip();
        if (label.length() > LABEL_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "label: " + LABEL_MAX + "자 이하로 입력해야 합니다");
        }
        return label;
    }
}
