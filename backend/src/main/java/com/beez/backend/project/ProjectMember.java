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

/**
 * 프로젝트 멤버십 (V2 project_member, V4 role_label·color). 제외·나가기는 active=false + left_at.
 * (project_id, account_id) 가 UNIQUE 라 나갔던 사람이 초대로 돌아오면 새 행이 아니라 이 행을 되살린다 ({@link #rejoin()}).
 * 생성자 팀장 멤버십은 -173, 초대 수락은 -174.
 */
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

    /** 담당 영역 (API 필드 domain, MEM-05) */
    private String area;

    @Column(name = "role_label")
    private String roleLabel;

    private String color;

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

    /** 초대를 수락한 사람의 일반 멤버십 (MEM-A05). */
    public static ProjectMember member(long projectId, long accountId) {
        ProjectMember m = new ProjectMember();
        m.projectId = projectId;
        m.accountId = accountId;
        m.role = ProjectRole.MEMBER;
        m.active = true;
        return m;
    }

    /**
     * 제외·나갔던 멤버십을 다시 활성화한다 (MEM-A05). 역할은 MEMBER 로, 표시 정보는 비운다 —
     * 예전 담당 영역이 지금도 맞는다고 볼 수 없어 팀장이 다시 정한다.
     */
    public void rejoin() {
        this.role = ProjectRole.MEMBER;
        this.active = true;
        this.leftAt = null;
        this.area = null;
        this.roleLabel = null;
        this.color = null;
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

    public String getArea() {
        return area;
    }

    public String getRoleLabel() {
        return roleLabel;
    }

    public String getColor() {
        return color;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLeftAt() {
        return leftAt;
    }
}
