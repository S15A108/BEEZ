package com.beez.backend.project;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRJ-A01 ~ A05. 컨트롤러는 HTTP ↔ 서비스 변환만 한다. 규칙·권한은 전부 ProjectService 안에 있다.
 * Idempotency-Key 는 아직 처리하지 않는다 (저장 방식 Q12 미결).
 */
@Tag(name = "project", description = "프로젝트 (PRJ)")
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @Operation(summary = "PRJ-A01 프로젝트 생성 (생성자가 팀장)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(CallerContext caller, @Valid @RequestBody CreateProjectRequest body) {
        return projects.create(caller, body);
    }

    @Operation(summary = "PRJ-A02 참여 프로젝트 목록 (커서)")
    @GetMapping
    public PageResponse<ProjectResponse> list(CallerContext caller,
                                              @RequestParam(name = "cursor", required = false) String cursor,
                                              @RequestParam(name = "limit", required = false) Integer limit) {
        return projects.listMine(caller, cursor, limit);
    }

    @Operation(summary = "PRJ-A03 프로젝트 상세")
    @GetMapping("/{id}")
    public ProjectResponse get(CallerContext caller, @PathVariable("id") Long projectId) {
        return projects.get(caller, projectId);
    }

    @Operation(summary = "PRJ-A04 프로젝트 정보 수정 (팀장)")
    @PatchMapping("/{id}")
    public ProjectResponse update(CallerContext caller, @PathVariable("id") Long projectId,
                                  @Valid @RequestBody UpdateProjectRequest body) {
        return projects.update(caller, projectId, body);
    }

    @Operation(summary = "PRJ-A05 프로젝트 삭제 (팀장, 이름 확인)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CallerContext caller, @PathVariable("id") Long projectId,
                       @Valid @RequestBody DeleteProjectRequest body) {
        projects.delete(caller, projectId, body);
    }
}
