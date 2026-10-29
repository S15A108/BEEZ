package com.beez.backend.designitem.repository;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Storage only. The service owns fingerprints, replay policy and the project-lock transaction. */
@Repository
public class DesignCommandRepository {
    public record StoredCommand(String fingerprint, String response) {
    }

    private final JdbcClient jdbc;

    public DesignCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteExpired(long project, long account, String operation, String key) {
        jdbc.sql("""
                DELETE FROM design_http_command WHERE project_id=:project AND account_id=:account
                  AND operation=:operation AND request_key=:key AND expires_at<=now()
                """).params(parameters(project, account, operation, key)).update();
    }

    public Optional<StoredCommand> find(long project, long account, String operation, String key) {
        return jdbc.sql("""
                SELECT fingerprint,response FROM design_http_command WHERE project_id=:project
                  AND account_id=:account AND operation=:operation AND request_key=:key
                """).params(parameters(project, account, operation, key))
                .query((rs, row) -> new StoredCommand(rs.getString("fingerprint"), rs.getString("response"))).optional();
    }

    public void insert(long project, long account, String operation, String key, String fingerprint, String response) {
        jdbc.sql("""
                INSERT INTO design_http_command(project_id,account_id,operation,request_key,fingerprint,response)
                VALUES(:project,:account,:operation,:key,:fingerprint,cast(:response AS jsonb))
                """).params(Map.of("project", project, "account", account, "operation", operation,
                        "key", key, "fingerprint", fingerprint, "response", response)).update();
    }

    private Map<String, Object> parameters(long project, long account, String operation, String key) {
        return Map.of("project", project, "account", account, "operation", operation, "key", key);
    }
}
