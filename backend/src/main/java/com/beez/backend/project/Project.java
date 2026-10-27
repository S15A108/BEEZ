package com.beez.backend.project;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 프로젝트 (V2 project). 권한 검사에 필요한 칸만 매핑했다. 생성·수정 API 는 -173 에서 넓힌다.
 * archived_at 이 찍힌 프로젝트는 삭제된 것으로 보고 모든 사용자에게 404 다 (PRJ-A05, -172 참고 2).
 */
@Entity
@Table(name = "project")
public class Project extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    @Column(name = "owner_account_id")
    private Long ownerAccountId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Project() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Long getOwnerAccountId() {
        return ownerAccountId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
