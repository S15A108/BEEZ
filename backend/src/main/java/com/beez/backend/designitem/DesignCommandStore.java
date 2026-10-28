package com.beez.backend.designitem;

import static com.beez.backend.designitem.DesignJson.*;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Used only within the transaction holding the project row lock. */
@Component
class DesignCommandStore {
    private final JdbcClient jdbc;
    DesignCommandStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    <T> Optional<T> replay(long project, long account, String operation, String key, Object input, Class<T> type) {
        validateKey(key);
        var parameters = Map.of("project", project, "account", account, "operation", operation, "key", key);
        jdbc.sql("""
                DELETE FROM design_http_command WHERE project_id=:project AND account_id=:account
                  AND operation=:operation AND request_key=:key AND expires_at<=now()
                """).params(parameters).update();
        return jdbc.sql("""
                SELECT fingerprint,response FROM design_http_command WHERE project_id=:project
                  AND account_id=:account AND operation=:operation AND request_key=:key
                """).params(parameters).query((rs, row) -> {
                    if (!rs.getString("fingerprint").equals(hash(input))) throw conflict("idempotency_conflict");
                    return JSON.readValue(rs.getString("response"), type);
                }).optional();
    }
    void save(long project, long account, String operation, String key, Object input, Object response) {
        validateKey(key);
        jdbc.sql("""
                INSERT INTO design_http_command(project_id,account_id,operation,request_key,fingerprint,response)
                VALUES(:project,:account,:operation,:key,:fingerprint,cast(:response AS jsonb))
                """).params(Map.of("project", project, "account", account, "operation", operation,
                        "key", key, "fingerprint", hash(input), "response", JSON.writeValueAsString(response))).update();
    }
    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Idempotency-Key는 제어 문자 없는 1~128자입니다.");
        }
    }
}
