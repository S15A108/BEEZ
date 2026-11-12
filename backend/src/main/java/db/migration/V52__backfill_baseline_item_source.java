package db.migration;

import com.beez.backend.designitem.support.SourceRemapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * S15P31A108-406 문서가 이미 바뀐 현재 기준의 설계 항목 출처를 바로잡는다.
 *
 * <p>-406 이전에 발행된 기준에서는 문서가 v66 → v85 로 바뀌어도 항목이 계속 v66 을 가리켜 근거 수집이 "기준에 없는 출처"로 끝났다.
 * 프로젝트마다 <b>현재 기준</b>에서, 출처 문서 버전이 같은 문서의 기준 문서 버전과 다른 항목을 찾아 옛 출처 버전 → 기준 문서 버전으로
 * 줄 위치를 옮긴 행을 baseline_item_source 에 넣는다(인접한 버전이 아니어도 된다). 옮길 수 없는 항목과 읽을 수 없는 데이터는
 * 예외 대신 NEEDS_CONFIRMATION 으로 넣어 배포(앱 기동)가 멈추지 않게 한다.
 *
 * <p>INSERT 만 한다 — baseline_item 이나 불변 트리거를 건드리지 않는다. 과거(현재가 아닌) 기준은 기록용이라 건드리지 않는다.
 * 이미 행이 있으면 건너뛰어 다시 실행해도 결과가 같다. Spring 서비스에 의존하지 않고 JDBC 와 {@link SourceRemapper}(무의존)만 쓴다.
 */
public class V52__backfill_baseline_item_source extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V52__backfill_baseline_item_source.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CANDIDATES = """
            select p.id as project_id, p.current_baseline_id as baseline_id, es.design_item_id,
                   es.source_document_version_id as from_version, bd.document_version_id as target_version,
                   es.source_locator::text as locator
              from project p
              join baseline_item_effective_source es on es.baseline_id = p.current_baseline_id
              join document_version sv on sv.id = es.source_document_version_id
              join baseline_document bd on bd.baseline_id = p.current_baseline_id and bd.document_id = sv.document_id
             where es.source_status is null and bd.document_version_id <> es.source_document_version_id
             order by p.id, bd.document_version_id, es.source_document_version_id, es.design_item_id
            """;

    private static final String INSERT = """
            insert into baseline_item_source(baseline_id, project_id, design_item_id, source_document_version_id,
                                             source_locator, source_status, remapped_from_document_version_id)
            values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
            on conflict (baseline_id, design_item_id) do nothing
            """;

    private record Candidate(long projectId, long baselineId, long itemId, long from, long target, String locator) { }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CANDIDATES); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                candidates.add(new Candidate(rows.getLong("project_id"), rows.getLong("baseline_id"),
                        rows.getLong("design_item_id"), rows.getLong("from_version"), rows.getLong("target_version"),
                        rows.getString("locator")));
            }
        }
        if (candidates.isEmpty()) return;
        Map<String, SourceRemapper> remappers = new HashMap<>();
        Map<Long, String> contents = new HashMap<>();
        Map<Long, int[]> perProject = new LinkedHashMap<>();
        try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            for (Candidate c : candidates) {
                SourceRemapper remapper = remappers.get(c.from() + ">" + c.target());
                if (remapper == null) {
                    remapper = SourceRemapper.of(content(connection, contents, c.from()), content(connection, contents, c.target()));
                    remappers.put(c.from() + ">" + c.target(), remapper);
                }
                SourceRemapper.Result result = remap(remapper, c.locator());
                String locator = c.locator();
                String status = "NEEDS_CONFIRMATION";
                if (result instanceof SourceRemapper.Remapped moved) {
                    locator = JSON.writeValueAsString(moved.locator());
                    status = "REMAPPED";
                }
                insert.setLong(1, c.baselineId());
                insert.setLong(2, c.projectId());
                insert.setLong(3, c.itemId());
                insert.setLong(4, c.target());
                insert.setString(5, locator == null ? "{}" : locator);
                insert.setString(6, status);
                insert.setLong(7, c.from());
                insert.addBatch();
                perProject.computeIfAbsent(c.projectId(), key -> new int[2])["REMAPPED".equals(status) ? 0 : 1]++;
            }
            insert.executeBatch();
        }
        perProject.forEach((project, counts) -> log.info(
                "baseline_item_source backfill project {}: {} moved, {} need confirmation", project, counts[0], counts[1]));
    }

    private static SourceRemapper.Result remap(SourceRemapper remapper, String locator) {
        try {
            return remapper.remap(locator == null ? null : JSON.readValue(locator, new TypeReference<Map<String, Object>>() { }));
        } catch (RuntimeException unreadable) {
            return new SourceRemapper.NeedsConfirmation("locator_unreadable");
        }
    }

    private static String content(Connection connection, Map<Long, String> cache, long version) throws SQLException {
        if (cache.containsKey(version)) return cache.get(version);
        String content = null;
        try (PreparedStatement statement = connection.prepareStatement("select content_text from document_version where id = ?")) {
            statement.setLong(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) content = rows.getString(1);
            }
        }
        cache.put(version, content);
        return content;
    }
}
