package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.*;
import static com.beez.backend.changeproposal.service.ProposalCommandService.error;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.changeproposal.repository.PublicationRepository;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.validation.DesignContentValidator;
import com.beez.backend.document.config.DocumentProperties;
import java.util.*;
import org.springframework.stereotype.Component;

/** Pure planning over one transaction snapshot. Never allocates IDs, locks rows or writes. */
@Component
public class PublishPlanner {
    public record PlannedItem(Item change, Map<String, Object> before, String title, int versionNo, int index) { }
    public record Plan(long previousBaselineId, int nextVersion, List<Map<String, Object>> documents,
                       List<Map<String, Object>> proposedDocuments, List<Map<String, Object>> existingItems,
                       List<PlannedItem> changes, List<PreviewItem> previewItems, Counts unchanged, ImpactRequest impact) { }
    private final PublicationRepository repository;
    private final BaselineRepository baselines;
    private final DesignContentValidator contents;
    private final DocumentProperties properties;
    public PublishPlanner(PublicationRepository repository, BaselineRepository baselines,
                          DesignContentValidator contents, DocumentProperties properties) {
        this.repository=repository; this.baselines=baselines; this.contents=contents; this.properties=properties;
    }
    public Plan plan(long project, long proposal, Map<String, Object> current) {
        if (!Set.of("PROPOSED","IN_REVIEW").contains(current.get("status")))
            throw error("invalid_transition", "제출되었거나 검토 중인 변경 제안만 승인할 수 있습니다.");
        long previous=id(current,"base_baseline_id");
        if (current.get("current_baseline_id") == null || previous != id(current,"current_baseline_id"))
            throw error("baseline_conflict", "현재 기준이 변경되었습니다. 최신 기준으로 다시 검토해 주세요.");
        int revision=((Number)current.get("revision_no")).intValue();
        Snapshot snapshot=JSON.readValue(current.get("snapshot").toString(),Snapshot.class);
        if (snapshot.legacy()) throw error("version_conflict", "리비전 원문을 확인할 수 없습니다. 제안을 다시 제출해 주세요.");
        checkImpactGate(project, proposal, revision);
        var documents=repository.documents(project,previous);
        var proposed=repository.proposedDocuments(project,proposal,revision);
        if (proposed.size()!=snapshot.documents().size()) throw conflict("version_conflict");
        var baseDocs=new HashMap<Long,Map<String,Object>>();
        documents.forEach(d -> baseDocs.put(id(d,"document_id"),d));
        for (var doc : proposed) {
            long document=id(doc,"document_id");
            var before=baseDocs.get(document);
            var submitted=snapshot.documents().stream().filter(d -> d.documentId().equals(Long.toString(document))).findFirst()
                    .orElseThrow(() -> conflict("version_conflict"));
            if (before==null || id(before,"id")!=id(doc,"base_document_version_id")
                    || !submitted.baseDocumentVersionId().equals(doc.get("base_document_version_id").toString())
                    || !Objects.equals(submitted.proposedDocumentVersionId(),doc.get("proposed_document_version_id").toString())
                    || !"DRAFT".equals(doc.get("status")) || doc.get("parent_version_id")==null
                    || id(doc,"parent_version_id")!=id(before,"id") || doc.get("change_proposal_id")==null
                    || id(doc,"change_proposal_id")!=proposal || !Objects.equals(submitted.afterContent(),doc.get("content_text")))
                throw conflict("version_conflict");
            if (!baselines.isIndexReady(id(doc,"proposed_document_version_id"),properties.embeddingProfile()))
                throw error("analysis_not_completed", "제안 문서 버전의 색인이 준비되지 않았습니다.");
        }
        var existing=repository.items(project,previous);
        var bases=new HashMap<Long,Map<String,Object>>(); existing.forEach(i -> bases.put(id(i,"design_item_id"),i));
        var changes=new ArrayList<PlannedItem>(); var preview=new ArrayList<PreviewItem>();
        var changed=new HashSet<Long>(); var impacts=new ArrayList<ChangedItem>();
        int index=0;
        for (Item item : snapshot.input().items()) {
            Map<String,Object> before=null;
            if (!"CREATE".equals(item.operation())) {
                long itemId=Long.parseLong(item.designItemId()); before=bases.get(itemId);
                if (!changed.add(itemId) || before==null || before.get("retired_at")!=null
                        || id(before,"id")!=Long.parseLong(item.baseDesignItemVersionId())
                        || !Objects.equals(item.kind(),before.get("kind")) || !"APPROVED".equals(before.get("status")))
                    throw conflict("version_conflict");
                impacts.add(new ChangedItem(itemId,id(before,"id"),null,item.operation()));
            }
            String title="RETIRE".equals(item.operation()) ? before.get("title").toString()
                    : contents.validate(item.kind(),item.schemaVersion(),item.proposedContent());
            int version=before==null ? 1 : ((Number)before.get("next_version_no")).intValue();
            changes.add(new PlannedItem(item,before,title,version,index));
            if (!"RETIRE".equals(item.operation())) preview.add(new PreviewItem(item.designItemId(),title,version,item.operation(),index));
            index++;
        }
        for (var item : existing) if (!changed.contains(id(item,"design_item_id")))
            preview.add(new PreviewItem(item.get("design_item_id").toString(),item.get("title").toString(),
                    ((Number)item.get("version_no")).intValue(),"UNCHANGED",null));
        int next=Math.addExact(((Number)repository.baseline(project,previous).get("version_no")).intValue(),1);
        return new Plan(previous,next,documents,proposed,existing,List.copyOf(changes),List.copyOf(preview),
                new Counts(existing.size()-changed.size(),documents.size()-proposed.size()),
                new ImpactRequest(null,previous,proposal,revision,current.get("snapshot_hash").toString(),List.copyOf(impacts),snapshot.input().reason()));
    }
    private void checkImpactGate(long project, long proposal, int revision) {
        // TODO(승인 게이트): after #197, inspect the current revision's analysis result. Always passes in this PR.
    }
}
