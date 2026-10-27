package com.beez.backend.project;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 프로젝트 멤버십 (V2 project_member). 제외·나가기는 active=false + left_at. 생성자 팀장 멤버십은 -173, 초대·위임 API 는 -174. */
@Entity
@Table(name = "project_member")
public class ProjectMember extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    private ProjectRole role;

    private boolean active;

    @Column(name = "left_at")
    private Instant leftAt;

    protected ProjectMember() {
    }

    /** 프로젝트를 만든 사람의 팀장 멤버십 (PRJ-A01). */
    public static ProjectMember leader(long projectId, long accountId) {
        ProjectMember m = new ProjectMember();
        m.projectId = projectId;
        m.accountId = accountId;
        m.role = ProjectRole.LEADER;
        m.active = true;
        return m;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public ProjectRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLeftAt() {
        return leftAt;
    }
}
