package com.beez.backend.designitem;

import static com.beez.backend.designitem.DesignJson.*;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.auth.CallerContext;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignItemCommandService {
    private final JdbcClient jdbc;
    private final DesignProjectLock locks;
    private final DesignCommandStore commands;
    private final DesignContentValidator content;
    private final DesignItemQueryService queries;
    public DesignItemCommandService(JdbcClient jdbc, DesignProjectLock locks, DesignCommandStore commands,
                                     DesignContentValidator content, DesignItemQueryService queries) {
        this.jdbc = jdbc; this.locks = locks; this.commands = commands; this.content = content; this.queries = queries;
    }

    @Transactional
    public DesignItemDtos.Item create(CallerContext caller, long project, DesignItemDtos.Create input, String key) {
        locks.leader(caller, project);
        var previous = commands.replay(project, caller.accountId(), "create-item", key, input, DesignItemDtos.Item.class);
        if (previous.isPresent()) return previous.get();
        requireInitial(project);
        String title = content.validate(input.kind(), input.schema_version(), input.content());
        if (input.source_document_version_id() != null) queries.requireSource(project, input.source_document_version_id());
        else if (input.source_location() != null) throw invalid("source_location에는 원문 버전이 필요합니다.");
        String prefix = switch (input.kind()) {
            case "REQUIREMENT" -> "REQ";
            case "API_CONTRACT" -> "API";
            case "DATA_CONTRACT" -> "ENT";
            case "POLICY" -> "POL";
            default -> "CON";
        };
        long sequence = jdbc.sql("SELECT coalesce(max(substring(key from :pattern)::bigint),0)+1 FROM design_item WHERE project_id=:project")
                .param("pattern", "^" + prefix + "-([0-9]+)$").param("project", project).query(Long.class).single();
        long item = jdbc.sql("INSERT INTO design_item(project_id,key,kind) VALUES(:project,:key,:kind) RETURNING id")
                .params(Map.of("project", project, "key", prefix + "-" + String.format(Locale.ROOT, "%03d", sequence), "kind", input.kind()))
                .query(Long.class).single();
        var params = new HashMap<String, Object>();
        params.put("item", item); params.put("title", title); params.put("content", canonical(input.content()));
        params.put("hash", hash(input.content())); params.put("actor", caller.accountId());
        params.put("source", input.source_document_version_id());
        params.put("locator", input.source_document_version_id() == null ? null
                : canonical(Map.of("location", Objects.toString(input.source_location(), ""))));
        long version = jdbc.sql("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,
                                                content_hash,source_document_version_id,source_locator,origin,created_by)
                VALUES(:item,1,'DRAFT',:title,1,cast(:content AS jsonb),:hash,:source,cast(:locator AS jsonb),'HUMAN',:actor)
                RETURNING id
                """).params(params).query(Long.class).single();
        var result = queries.get(project, item, version);
        commands.save(project, caller.accountId(), "create-item", key, input, result);
        return result;
    }

    @Transactional
    public DesignItemDtos.Item update(CallerContext caller, long item, DesignItemDtos.Update input) {
        locks.leader(caller, input.project_id());
        requireInitial(input.project_id());
        var current = editable(input.project_id(), item, input.expected_version_id(), input.expected_content_hash());
        String title = content.validate(current.kind(), current.schema_version(), input.content());
        int changed = jdbc.sql("""
                UPDATE design_item_version SET title=:title,content=cast(:content AS jsonb),content_hash=:hash,updated_at=now()
                 WHERE id=:version AND design_item_id=:item AND status='DRAFT' AND content_hash=:expected
                """).params(Map.of("title", title, "content", canonical(input.content()), "hash", hash(input.content()),
                        "version", input.expected_version_id(), "item", item, "expected", input.expected_content_hash())).update();
        if (changed != 1) throw conflict("version_conflict");
        return queries.get(input.project_id(), item, input.expected_version_id());
    }

    @Transactional
    public void exclude(CallerContext caller, long project, long item, long version, String expectedHash) {
        locks.leader(caller, project);
        requireInitial(project);
        editable(project, item, version, expectedHash);
        int changed = jdbc.sql("""
                UPDATE design_item_version SET status='REJECTED',updated_at=now()
                 WHERE id=:version AND design_item_id=:item AND status='DRAFT' AND content_hash=:hash
                """).params(Map.of("version", version, "item", item, "hash", expectedHash)).update();
        if (changed != 1) throw conflict("version_conflict");
    }

    private DesignItemDtos.Item editable(long project, long item, long version, String expectedHash) {
        if (item < 1 || version < 1 || expectedHash == null || expectedHash.isBlank()) throw invalid("대상 버전과 해시가 필요합니다.");
        var current = queries.get(project, item, version);
        if (!"DRAFT".equals(current.status()) || !Objects.equals(current.content_hash(), expectedHash)) throw conflict("version_conflict");
        if (jdbc.sql("SELECT id FROM design_item WHERE id=:item AND retired_at IS NULL")
                .param("item", item).query(Long.class).optional().isEmpty()) throw AccessErrors.notFound("retired_design_item");
        return current;
    }
    void requireInitial(long project) {
        if (jdbc.sql("SELECT id FROM baseline WHERE project_id=:project LIMIT 1")
                .param("project", project).query(Long.class).optional().isPresent()) throw conflict("baseline_exists");
    }
}
