package com.beez.backend.project;

import com.beez.backend.account.Account;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designchange.dto.BaselineSummaryResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRJ-A01 ~ A05 (S15P31A108-173). 권한 검사는 각 메서드 첫 줄 (06-project-access.md).
 * 생성·목록은 프로젝트와 무관한 USER 권한, 상세는 MEMBER, 수정·삭제는 사람 세션의 팀장.
 */
@Service
public class ProjectService {

    static final int NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 2000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ProjectAccessChecker access;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;

    public ProjectService(ProjectAccessChecker access, ProjectRepository projects, ProjectMemberRepository members,
                          ApplicationEventPublisher events, JdbcClient jdbc) {
        this.access = access;
        this.projects = projects;
        this.members = members;
        this.events = events;
        this.jdbc = jdbc;
    }

    /** PRJ-A01. 프로젝트와 생성자의 LEADER 멤버십을 한 트랜잭션에서 만든다. */
    @Transactional
    public ProjectResponse create(CallerContext caller, CreateProjectRequest req) {
        Account me = access.requireUser(caller);
        Project project = projects.save(Project.create(requireName(req.name()), blankToNull(req.description()), me.getId()));
        members.save(ProjectMember.leader(project.getId(), me.getId()));
        events.publishEvent(new ProjectCreatedEvent(project.getId(), me.getId()));
        return ProjectResponse.of(project, ProjectRole.LEADER, null);
    }

    /** PRJ-A02. 내가 활성 멤버인 삭제 안 된 프로젝트만, id 내림차순 커서. */
    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> listMine(CallerContext caller, String cursor, Integer limit) {
        Account me = access.requireUser(caller);
        int size = normalizeLimit(limit);
        long beforeId = Long.MAX_VALUE;
        // 빈 cursor 는 첫 페이지 (작업 목록 TaskQueryService.parseCursor 와 같게)
        if (cursor != null && !cursor.isBlank()) {
            beforeId = Ids.parse(cursor, "cursor");
        }
        // limit+1 개를 읽는다. 다 차면 다음 페이지가 있다는 뜻.
        List<MyProjectRow> rows = projects.findMyProjects(me.getId(), beforeId, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<MyProjectRow> page = rows;
        if (hasMore) {
            page = rows.subList(0, size);
        }

        List<Long> baselineIds = new ArrayList<>();
        for (MyProjectRow row : page) {
            if (row.project().getCurrentBaselineId() != null) {
                baselineIds.add(row.project().getCurrentBaselineId());
            }
        }
        Map<Long, BaselineSummaryResponse> baselines = baselineSummaries(baselineIds);
        List<ProjectResponse> items = new ArrayList<>();
        for (MyProjectRow row : page) {
            Project p = row.project();
            items.add(ProjectResponse.of(p, row.role(), baselines.get(p.getCurrentBaselineId())));
        }
        String nextCursor = null;
        if (hasMore) {
            nextCursor = Ids.str(page.get(page.size() - 1).project().getId());
        }
        return PageResponse.of(items, hasMore, nextCursor);
    }

    /** PRJ-A03 */
    @Transactional(readOnly = true)
    public ProjectResponse get(CallerContext caller, long projectId) {
        ProjectActor actor = access.requireMember(caller, projectId);
        // 권한 검사와 이 조회 사이에 삭제가 커밋됐을 수 있어 archived_at 을 다시 본다
        Project project = projects.findById(projectId)
                .filter(p -> p.getArchivedAt() == null)
                .orElseThrow(() -> AccessErrors.notFound("project_archived_or_missing project=" + projectId));
        return toResponse(project, actor.role());
    }

    /** PRJ-A04. 이름·설명만 바꾼다. 멤버십·기준 설계는 이 요청으로 바꾸지 않는다. */
    @Transactional
    public ProjectResponse update(CallerContext caller, long projectId, UpdateProjectRequest req) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        if (req.name() == null && req.description() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "변경할 필드가 하나 이상 필요합니다");
        }
        Project project = lockActive(projectId);
        if (req.name() != null) {
            project.rename(requireName(req.name()));
        }
        if (req.description() != null) {
            project.changeDescription(blankToNull(req.description()));
        }
        // updated_at 은 flush 때 채워지므로 응답 전에 내보낸다
        projects.saveAndFlush(project);
        return toResponse(project, actor.role());
    }

    /**
     * PRJ-A05. 숨김 처리(archived_at)하고 같은 트랜잭션에서 그 프로젝트의 에이전트 토큰을 모두 폐기한다 (-172 합의 4).
     * 멤버십·원본·이력은 지우지 않는다. 권한 검사가 archived_at 을 보므로 다음 요청부터 모두 404 다.
     */
    @Transactional
    public void delete(CallerContext caller, long projectId, DeleteProjectRequest req) {
        access.requireHumanLeader(caller, projectId);
        Project project = lockActive(projectId);
        if (!project.getName().equals(req.confirmName())) {
            throw new ApiException(HttpStatus.CONFLICT, "confirmation_mismatch", "프로젝트 이름이 일치하지 않습니다");
        }
        Instant now = Instant.now();
        project.archive(now);
        projects.revokeAllTokens(projectId, now);
    }

    /**
     * 행을 잠그고 읽은 뒤 삭제 여부를 다시 본다. 권한 검사와 잠금 사이에 다른 요청이 삭제를 끝냈을 수 있다.
     */
    private Project lockActive(long projectId) {
        Project project = projects.findForUpdate(projectId)
                .orElseThrow(() -> AccessErrors.notFound("project_missing project=" + projectId));
        if (project.getArchivedAt() != null) {
            throw AccessErrors.notFound("project_archived project=" + projectId);
        }
        return project;
    }

    private ProjectResponse toResponse(Project project, ProjectRole myRole) {
        BaselineSummaryResponse baseline = null;
        if (project.getCurrentBaselineId() != null) {
            baseline = baselineSummaries(List.of(project.getCurrentBaselineId())).get(project.getCurrentBaselineId());
        }
        return ProjectResponse.of(project, myRole, baseline);
    }

    /**
     * 기준 설계 요약을 한 번에 읽는다 (목록에서 프로젝트마다 쿼리하지 않게).
     * baseline 엔티티(오서현, S15P31A108-98)가 아직 없어 SQL 로 읽는다 (ProjectDesignLookup 과 같은 이유).
     */
    private Map<Long, BaselineSummaryResponse> baselineSummaries(List<Long> baselineIds) {
        Map<Long, BaselineSummaryResponse> out = new HashMap<>();
        if (baselineIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select id, version_no, published_at from baseline where id in (:ids)")
                .param("ids", baselineIds)
                .query((rs, rowNum) -> new BaselineSummaryResponse(Ids.str(rs.getLong("id")), rs.getInt("version_no"),
                        rs.getTimestamp("published_at").toInstant()))
                .list()
                .forEach(b -> out.put(Long.parseLong(b.id()), b));
        return out;
    }

    /** 앞뒤 공백을 뺀 이름. 비었거나 길면 400. */
    private static String requireName(String raw) {
        String name = raw.strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "name: 1~" + NAME_MAX + "자로 입력해야 합니다");
        }
        return name;
    }

    /** 공백뿐인 설명은 "설명 없음"(null)으로 저장한다. PATCH 에서는 "" 이 설명 지우기다. */
    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
