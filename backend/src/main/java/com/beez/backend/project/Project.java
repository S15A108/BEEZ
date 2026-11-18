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
 * 프로젝트 (V2 project). 생성·수정·삭제는 ProjectService(-173).
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

    /**
     * 기준 설계 발행이 바꾸는 칸이라 이 엔티티로는 읽기만 한다.
     * 쓰기를 열어 두면 이름 수정 같은 저장이 읽은 시점의 옛 값을 덮어쓸 수 있다.
     */
    @Column(name = "current_baseline_id", insertable = false, updatable = false)
    private Long currentBaselineId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Project() {
    }

    public static Project create(String name, String description, long ownerAccountId) {
        Project p = new Project();
        p.name = name;
        p.description = description;
        p.ownerAccountId = ownerAccountId;
        return p;
    }

    public void rename(String name) {
        this.name = name;
    }

    /** null 이면 설명을 지운다. "생략은 유지"는 서비스가 판단해 이 메서드를 부르지 않는 것으로 처리한다. */
    public void changeDescription(String description) {
        this.description = description;
    }

    /** 삭제(숨김). 데이터는 남긴다 (PRJ-A05 처리 규칙). */
    public void archive(Instant now) {
        this.archivedAt = now;
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

    public Long getCurrentBaselineId() {
        return currentBaselineId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
