package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 계획이 가리키는 근거 패킷(dev_context_packet)이 같은 프로젝트·같은 작업 것인지 확인한다.
 * 패킷 엔티티는 S15P31A108-165 에서 만든다.
 *
 * <p>-172 합의 5번: 번호로 먼저 찾고 나중에 프로젝트를 비교하지 않는다. 조건에 프로젝트·작업을 같이 건다.
 * 다른 프로젝트 패킷과 없는 패킷은 같은 응답이다 (존재를 드러내지 않음).
 */
@Component
public class ContextPacketLookup {

    private final JdbcClient jdbc;

    public ContextPacketLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param taskId 작업 생성 시점에는 아직 없으므로 null 허용.
     *               null 이 아니면 패킷의 task_id 가 비어 있거나 이 작업이어야 한다.
     */
    public void requireBelongsTo(Long packetId, Long projectId, Long taskId) {
        Integer count = jdbc.sql("""
                        select count(*)
                          from dev_context_packet
                         where id = :packetId
                           and project_id = :projectId
                           and (cast(:taskId as bigint) is null or task_id is null or task_id = :taskId)
                        """)
                .param("packetId", packetId)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query(Integer.class)
                .single();
        if (count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "근거 패킷을 찾을 수 없습니다: " + packetId);
        }
    }
}
