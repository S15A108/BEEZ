package com.beez.backend.context.repository;

import com.beez.backend.context.entity.DevContextPacket;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DevContextPacketRepository extends JpaRepository<DevContextPacket, Long> {

    Optional<DevContextPacket> findByIdAndProjectId(Long id, Long projectId);
}
