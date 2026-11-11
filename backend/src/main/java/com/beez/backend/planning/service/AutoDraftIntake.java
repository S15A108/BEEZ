package com.beez.backend.planning.service;

import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.document.config.DocumentProperties;
import com.beez.backend.planning.repository.DesignDraftRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문서 4종이 모두 승인·색인되고 기준 설계가 없으면, 사람이 누르지 않아도 문서 버전마다 문서 단독 생성 작업을 접수한다(S15P31A108-389).
 *
 * <p>접수만 하고 AI 는 부르지 않는다({@link DesignDraftRunner} 가 처리). 문서 버전마다 자동 접수는 한 번뿐이다 — DB 의
 * {@code ux_design_draft_job_auto} 가 동시에 두 틱이 와도 한 건만 받는다. 실패한 작업의 재시도는 팀장이 누른다(자동으로 다시 접수하지 않음).
 * 작업의 요청자는 프로젝트의 팀장 계정이고, 자동인지는 {@code trigger_kind='AUTO'} 로 구분한다.
 */
@Service
public class AutoDraftIntake {
    private static final Logger log = LoggerFactory.getLogger(AutoDraftIntake.class);
    static final int BATCH = 10;

    private final DesignDraftRepository drafts;
    private final DesignDraftService service;
    private final DesignProjectRepository projects;
    private final BaselineRepository baselines;
    private final DocumentProperties properties;
    private final TransactionTemplate tx;

    public AutoDraftIntake(DesignDraftRepository drafts, DesignDraftService service, DesignProjectRepository projects,
                           BaselineRepository baselines, DocumentProperties properties, PlatformTransactionManager manager) {
        this.drafts = drafts; this.service = service; this.projects = projects; this.baselines = baselines; this.properties = properties;
        this.tx = new TransactionTemplate(manager);
    }

    /** 이번에 새로 접수한 작업 수. */
    public int runOnce() {
        var byProject = new LinkedHashMap<Long, List<Long>>();
        for (var row : drafts.autoTargets(BATCH * 4)) {
            byProject.computeIfAbsent(((Number) row.get("project_id")).longValue(), k -> new ArrayList<>()).add(((Number) row.get("version_id")).longValue());
        }
        int submitted = 0;
        for (var entry : byProject.entrySet()) {
            try {
                Integer n = tx.execute(status -> intake(entry.getKey()));
                if (n != null) submitted += n;
            } catch (DuplicateKeyException e) {
                // 다른 인스턴스가 먼저 접수했다 — 정상이다.
            } catch (RuntimeException e) {
                log.error("자동 설계 항목 후보 접수 실패 project={} 예외 종류={}", entry.getKey(), e.getClass().getSimpleName());
            }
        }
        return submitted;
    }

    /** 프로젝트 잠금 아래에서 조건을 다시 확인하고 접수한다. 4종 모두 준비되지 않았으면 하나도 접수하지 않는다. */
    private int intake(long project) {
        if (!projects.lockActiveProject(project) || drafts.hasBaseline(project)) return 0;
        var versions = drafts.latestApprovedVersions(project);
        var types = new java.util.HashSet<String>();
        var docs = new ArrayList<Map<String, Object>>();
        for (var row : versions) {
            long version = ((Number) row.get("version_id")).longValue();
            var doc = drafts.documentVersion(project, version);
            if (doc.isEmpty() || !"COMPLETED".equals(doc.get().get("processing_status"))
                    || !(doc.get().get("content_text") instanceof String text) || text.isBlank()
                    || !baselines.isIndexReady(version, properties.embeddingProfile())) return 0;
            types.add(row.get("type").toString());
            docs.add(doc.get());
        }
        if (!types.containsAll(List.of("PLAN", "REQ_SPEC", "ERD", "API_SPEC"))) return 0;
        var leader = drafts.firstLeader(project);
        if (leader.isEmpty()) return 0;
        int submitted = 0;
        for (var doc : docs) {
            long version = ((Number) doc.get("document_version_id")).longValue();
            if (drafts.autoJobExists(version) || drafts.activeJobExists(version)) continue;
            String textHash = String.valueOf(doc.get("text_hash"));
            String key = "auto-" + version + "-" + textHash.substring(Math.max(0, textHash.length() - 12));
            service.intakeDocumentOnly(project, doc, leader.get(), key, "AUTO");
            submitted++;
        }
        return submitted;
    }
}
