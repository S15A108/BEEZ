package com.beez.backend.designitem.service;

import static com.beez.backend.designitem.support.DesignJson.object;

import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.support.DesignJson;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The full project draft, read under repeatable-read or the command's project lock. */
@Service
public class DesignSnapshotService {
    private final BaselineRepository baselines;
    private final DesignItemRepository designItems;
    private final DesignProjectRepository projects;

    public DesignSnapshotService(BaselineRepository baselines, DesignItemRepository designItems, DesignProjectRepository projects) {
        this.baselines = baselines;
        this.designItems = designItems;
        this.projects = projects;
    }

    public List<Map<String, Object>> documents(long project) {
        return baselines.findLatestDocuments(project);
    }

    public List<Map<String, Object>> items(long project) {
        return designItems.findDrafts(project);
    }

    public String hash(long project) {
        var docs = documents(project).stream().map(r -> List.of(r.get("document_id"), r.get("type"),
                r.get("id"), r.get("content_hash"), r.get("status"))).toList();
        var items = items(project).stream().map(r -> {
            var source = r.get("source_document_version_id");
            var locator = r.get("source_locator");
            return List.of(r.get("design_item_id"), r.get("key"), r.get("kind"), r.get("id"),
                    r.get("schema_version"), r.get("content_hash"), source == null ? "" : source,
                    locator == null ? Map.of() : object(locator));
        }).toList();
        Long baseline = projects.currentBaselineId(project);
        return DesignJson.hash(Map.of("project_id", project, "baseline_id", baseline == null ? "" : baseline,
                "documents", docs, "items", items, "working_copies", baselines.workingCopyStates(project)));
    }
}
