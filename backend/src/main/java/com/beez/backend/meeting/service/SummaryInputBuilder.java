package com.beez.backend.meeting.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.text.NormalizedText;
import com.beez.backend.meeting.config.MeetingSummaryProperties;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.EntryRow;
import com.beez.backend.meeting.repository.MeetingSummaryRepository;
import com.beez.backend.meeting.service.SummaryResultValidator.DocInfo;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.service.TaskQueryService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * AI-A08 SUMMARY 입력 조립 (S15P31A108-235). 읽기 전용이다.
 *
 * <ul>
 *   <li>{@code entries}: 정리 범위({@code through_entry_id} 까지)의 사람 기록(TEXT)과 비비의 첫 메시지(CONFLICT_BRIEF)만. 이전 SUMMARY·분석 기록은
 *       넣지 않는다(이전 정리는 {@code previous_summary} 로 따로). 최근 것부터 담는다.</li>
 *   <li>{@code related_tasks}: 회의가 가리키는 작업의 상세.</li>
 *   <li>{@code approved_items}: 그 작업들의 현재 계획이 참조하는 설계 항목의 <b>지금 기준</b> 승인 버전.</li>
 *   <li>{@code documents}: 위 항목의 출처 문서의 <b>지금 기준</b> 승인본 원문(utf8-lf-v1 정규화). 줄 번호가 원문과 맞아야 해서 통째로만 보낸다.</li>
 * </ul>
 *
 * <p><b>요청 크기는 하나의 바이트 예산</b>({@link MeetingSummaryProperties#maxRequestBytes()})을 나눠 쓴다(한글은 글자당 3바이트).
 * 배분 우선순위: ① 고정부(작업·이전 정리·피드백) ② 최근 기록 예약분(최소 한 건, 못 담으면 AI 호출 전 400) ③ 설계 항목 ④ 문서(통째로만) ⑤ 남는 만큼 나머지 기록.
 * 건너뛴 것은 모두 {@code notes} 에 사람이 읽는 문장으로 남기고, 조립 뒤에 실제 직렬화 바이트를 한 번 더 확인한다.
 */
@Component
class SummaryInputBuilder {
    private static final Logger log = LoggerFactory.getLogger(SummaryInputBuilder.class);

    /** request_key(약 100바이트)와 JSON 구분자·후행 필드를 위한 여유 */
    private static final int SLACK_BYTES = 2_048;

    /**
     * @param payload 요청 본문(request_key 제외 — 호출마다 새로 붙인다)
     * @param entryIds 입력에 실제로 실은 기록 ID(근거 발언 검증용)
     * @param documents 입력에 실은 문서(출력 검증·호출 뒤 기준 확인용)
     * @param notes 입력을 줄이거나 빼서 생성 근거 unresolved 에 남길 안내
     */
    record Built(Map<String, Object> payload, Set<Long> entryIds, Map<Long, DocInfo> documents, List<String> notes) { }

    private final MeetingRepository meetings;
    private final MeetingSummaryRepository summaries;
    private final TaskQueryService tasks;
    private final TaskPlanDesignRefRepository designRefs;
    private final MeetingSummaryProperties limits;

    SummaryInputBuilder(MeetingRepository meetings, MeetingSummaryRepository summaries, TaskQueryService tasks,
                        TaskPlanDesignRefRepository designRefs, MeetingSummaryProperties limits) {
        this.meetings = meetings;
        this.summaries = summaries;
        this.tasks = tasks;
        this.designRefs = designRefs;
        this.limits = limits;
    }

    /** previousSummary·feedback 은 수정 요청(MTG-A12)일 때만 */
    Built build(CallerContext caller, long projectId, long meetingId, long throughEntryId,
                Map<String, Object> previousSummary, String feedback) {
        List<String> notes = new ArrayList<>();
        long budget = limits.maxRequestBytes() - SLACK_BYTES;

        // ① 고정부 — 관련 작업, 이전 정리, 피드백
        List<Long> taskIds = meetings.taskIds(List.of(meetingId)).getOrDefault(meetingId, List.of());
        List<Map<String, Object>> relatedTasks = new ArrayList<>();
        Set<Long> designItemIds = new LinkedHashSet<>();
        for (Long taskId : taskIds) {
            TaskDetailResponse detail;
            try {
                detail = tasks.getTask(caller, projectId, taskId);
            } catch (ApiException e) {
                log.warn("meeting {} task {} not readable for the summary: {}", meetingId, taskId, e.getMessage());
                notes.add("작업 " + taskId + "을 읽지 못해 정리 입력에서 제외했어요.");
                continue;
            }
            relatedTasks.add(JSON.convertValue(detail, new TypeReference<Map<String, Object>>() { }));
            if (detail.currentPlanRevision() != null && detail.currentPlanRevision().id() != null) {
                for (TaskPlanDesignRef ref : designRefs.findByPlanRevisionId(Long.parseLong(detail.currentPlanRevision().id()))) {
                    designItemIds.add(ref.getDesignItemId());
                }
            }
        }
        long remaining = budget - bytes(relatedTasks) - (previousSummary == null ? 0 : bytes(previousSummary))
                - (feedback == null ? 0 : bytes(feedback)) - bytes(Map.of("project_id", Long.toString(projectId), "mode", "SUMMARY"));
        if (remaining < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "정리에 보낼 작업·이전 정리가 너무 커서 요청할 수 없어요.");
        }

        // ② 최근 기록 예약분 — 설계 항목보다 먼저 잡는다. 최근 것부터 담되 가장 최근 한 건은 반드시 담는다
        List<EntryRow> newest = meetings.summarizableNewestFirst(projectId, meetingId, throughEntryId, limits.maxEntries());
        List<Map<String, Object>> reserved = new ArrayList<>();      // 최근 것부터
        int next = 0;
        long reservedBytes = 0;
        long reserve = Math.min(limits.recentEntriesReserveBytes(), remaining);
        for (; next < newest.size(); next++) {
            Map<String, Object> view = entryView(newest.get(next));
            long size = bytes(view) + 1;
            if (!reserved.isEmpty() && reservedBytes + size > reserve) break;
            if (reservedBytes + size > remaining) break;
            reserved.add(view);
            reservedBytes += size;
        }
        if (reserved.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    newest.isEmpty() ? "정리할 기록이 없어요." : "가장 최근 기록이 요청 크기 한도를 넘어 정리를 요청할 수 없어요.");
        }
        remaining -= reservedBytes;
        // ③ 설계 항목 — 키 순서로 예산이 허용하는 만큼
        Long baseline = summaries.currentBaseline(projectId);
        List<Map<String, Object>> itemRows = baseline == null ? List.of() : summaries.baselineItems(baseline, designItemIds, limits.maxItems());
        List<Map<String, Object>> approvedItems = new ArrayList<>();
        Set<Long> documentIds = new TreeSet<>();
        int itemsSkipped = 0;
        for (Map<String, Object> row : itemRows) {
            Map<String, Object> view = itemView(row);
            long size = bytes(view) + 1;
            if (size > remaining) {
                itemsSkipped++;
                continue;
            }
            remaining -= size;
            approvedItems.add(view);
            if (row.get("source_document_id") != null) documentIds.add(((Number) row.get("source_document_id")).longValue());
        }
        if (itemsSkipped > 0) notes.add("설계 항목 " + itemRows.size() + "개 중 " + approvedItems.size() + "개만 전달했어요(요청 크기 한도).");
        else if (designItemIds.size() > itemRows.size() && itemRows.size() >= limits.maxItems()) {
            notes.add("설계 항목 " + designItemIds.size() + "개 중 " + itemRows.size() + "개만 전달했어요.");
        }

        // ④ 문서 — 통째로만. 개수·문서당 바이트·남은 예산 안에서
        List<Map<String, Object>> documents = new ArrayList<>();
        Map<Long, DocInfo> docInfos = new LinkedHashMap<>();
        if (baseline != null) {
            int skipped = 0;
            for (Map<String, Object> row : summaries.baselineDocuments(baseline, documentIds)) {
                String content = row.get("content_text") instanceof String s ? NormalizedText.normalize(s) : "";
                long documentId = ((Number) row.get("document_id")).longValue();
                long versionId = ((Number) row.get("id")).longValue();
                var view = new LinkedHashMap<String, Object>();
                view.put("document_id", Long.toString(documentId));
                view.put("document_version_id", Long.toString(versionId));
                view.put("text", content);
                long size = bytes(view) + 1;
                if (content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > limits.maxDocumentBytes()
                        || documents.size() >= limits.maxDocuments() || size > remaining) {
                    skipped++;
                    continue;
                }
                remaining -= size;
                documents.add(view);
                docInfos.put(documentId, new DocInfo(documentId, versionId, ((Number) row.get("version_no")).intValue(),
                        NormalizedText.lines(content), NormalizedText.endsWithLf(content)));
            }
            if (skipped > 0) notes.add("관련 문서 " + skipped + "개는 원문이 없거나 크기 한도를 넘어 보내지 않았어요.");
        }

        // ⑤ 남는 만큼 나머지 기록(최근 것부터 이어서)
        List<Map<String, Object>> older = new ArrayList<>();         // 최근 것부터
        for (; next < newest.size(); next++) {
            Map<String, Object> view = entryView(newest.get(next));
            long size = bytes(view) + 1;
            if (size > remaining) break;
            remaining -= size;
            older.add(view);
        }
        List<Map<String, Object>> entries = new ArrayList<>(reserved);   // 최근 것부터: 예약분 뒤에 더 오래된 기록
        entries.addAll(older);
        Collections.reverse(entries);                                // 오래된 순으로 뒤집는다
        int excluded = meetings.countSummarizableThrough(meetingId, throughEntryId) - entries.size();
        if (excluded > 0) notes.add("앞쪽 기록 " + excluded + "건은 입력 한도를 넘어 제외했어요.");
        Set<Long> entryIds = new LinkedHashSet<>();
        for (Map<String, Object> view : entries) entryIds.add(Long.parseLong((String) view.get("id")));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("project_id", Long.toString(projectId));
        payload.put("mode", "SUMMARY");
        payload.put("entries", entries);
        payload.put("approved_items", approvedItems);
        payload.put("related_tasks", relatedTasks);
        payload.put("documents", documents);
        if (previousSummary != null) payload.put("previous_summary", previousSummary);
        if (feedback != null) payload.put("feedback", feedback);
        // 조립 뒤 실제 직렬화 바이트를 한 번 더 확인한다
        long actual = bytes(payload);
        if (actual > limits.maxRequestBytes()) {
            log.error("meeting {} summary input {} bytes exceeds the budget {}", meetingId, actual, limits.maxRequestBytes());
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "정리에 보낼 내용이 너무 커서 요청할 수 없어요.");
        }
        return new Built(payload, entryIds, docInfos, notes);
    }

    /** JSON 직렬화 바이트 수. 한글은 글자당 3바이트다 */
    private static long bytes(Object value) {
        return JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, Object> entryView(EntryRow row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", Ids.str(row.id()));
        view.put("meeting_id", Ids.str(row.meetingId()));
        view.put("kind", row.kind());
        view.put("text", row.body());
        view.put("author_kind", row.authorKind());
        if (row.authorAccountId() != null) view.put("author_account_id", Ids.str(row.authorAccountId()));
        if (row.authorMemberId() != null) view.put("author_member_id", Ids.str(row.authorMemberId()));
        if (row.authorName() != null) view.put("author_name", row.authorName());
        if (row.refs() != null) view.put("refs", JSON.readValue(row.refs(), new TypeReference<List<MeetingRef>>() { }));
        view.put("created_at", row.createdAt().toString());
        return view;
    }

    private static Map<String, Object> itemView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", row.get("id").toString());
        view.put("design_item_id", row.get("design_item_id").toString());
        view.put("key", row.get("key"));
        view.put("kind", row.get("kind"));
        view.put("version_no", row.get("version_no"));
        view.put("status", row.get("status"));
        view.put("schema_version", row.get("schema_version"));
        view.put("content", JSON.readValue(row.get("content").toString(), Object.class));
        if (row.get("source_document_version_id") != null && row.get("source_document_id") != null) {
            var source = new LinkedHashMap<String, Object>();
            source.put("document_id", row.get("source_document_id").toString());
            source.put("document_version_id", row.get("source_document_version_id").toString());
            source.put("version_no", row.get("source_version_no"));
            source.put("location", location(row.get("source_locator")));
            view.put("source", source);
        }
        return view;
    }

    /** 출처 위치를 사람이 읽는 문자열로. 행 범위가 있으면 "L3-L8", 없으면 "원문 전체" */
    private static String location(Object locator) {
        if (locator == null) return "원문 전체";
        try {
            var node = JSON.readTree(locator.toString());
            if (node.hasNonNull("start_line") && node.hasNonNull("end_line")) {
                return "L" + node.get("start_line").asInt() + "-L" + node.get("end_line").asInt();
            }
        } catch (RuntimeException e) {
            log.debug("source_locator not readable: {}", e.getMessage());
        }
        return "원문 전체";
    }
}
