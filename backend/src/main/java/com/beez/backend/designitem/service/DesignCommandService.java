package com.beez.backend.designitem.service;

import static com.beez.backend.designitem.support.DesignJson.conflict;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;
import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.designitem.repository.DesignCommandRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Runs inside the command service transaction holding the project row lock. */
@Service
public class DesignCommandService {
    private final DesignCommandRepository commands;

    public DesignCommandService(DesignCommandRepository commands) {
        this.commands = commands;
    }

    public <T> Optional<T> replay(long project, long account, String operation, String key, Object input, Class<T> type) {
        validateKey(key);
        commands.deleteExpired(project, account, operation, key);
        return commands.find(project, account, operation, key).map(stored -> {
            if (!stored.fingerprint().equals(hash(input))) {
                throw conflict("idempotency_conflict");
            }
            return JSON.readValue(stored.response(), type);
        });
    }

    public void save(long project, long account, String operation, String key, Object input, Object response) {
        validateKey(key);
        commands.insert(project, account, operation, key, hash(input), JSON.writeValueAsString(response));
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Idempotency-Key는 제어 문자 없는 1~128자입니다.");
        }
    }
}
