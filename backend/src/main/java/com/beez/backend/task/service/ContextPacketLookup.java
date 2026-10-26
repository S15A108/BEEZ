package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 계획이 가리키는 근거 패킷(dev_context_packet)이 같은 프로젝트·같은 작업 것인지 확인한다.
 * 패킷 엔티티는 S15P31A108-165 에서 만든다. 여기서는 존재·소속만 본다.
 */
@Component
public class ContextPacketLookup {

    private final JdbcClient jdbc;

    public ContextPacketLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param taskId 작업 생성 시점에는 아직 없으므로 null 허용. null 이 아니면 패킷의 task_id 가 null 이거나 같아야 한다.
     */
    public void requireBelongsTo(Long packetId, Long projectId, Long taskId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select project_id, task_id from dev_context_packet where id = :id
                        """)
                .param("id", packetId)
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "근거 패킷을 찾을 수 없습니다: " + packetId);
        }
        Map<String, Object> row = rows.get(0);
        Long packetProject = ((Number) row.get("project_id")).longValue();
        if (!packetProject.equals(projectId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "근거 패킷이 다른 프로젝트 것입니다");
        }
        Object packetTaskRaw = row.get("task_id");
        if (packetTaskRaw != null && taskId != null) {
            Long packetTask = ((Number) packetTaskRaw).longValue();
            if (!packetTask.equals(taskId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "근거 패킷이 다른 작업 것입니다");
            }
        }
    }
}
