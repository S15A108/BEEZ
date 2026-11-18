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
 * 프로젝트 멤버십 (V2 project_member, V4 role_label·color, V11 left_reason). 제외·나가기는 active=false + left_at + left_reason.
 * (project_id, account_id) 가 UNIQUE 라 나갔던 사람이 초대로 돌아오면 새 행이 아니라 이 행을 되살린다 ({@link #rejoin()}).
 * 생성자 팀장 멤버십은 -173, 초대 수락은 -174, 표시 수정·위임·제외·나가기는 -212.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "left_reason")
    private MembershipEndReason leftReason;

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
        this.leftReason = null;
        this.area = null;
        this.roleLabel = null;
        this.color = null;
    }

    /** 담당 영역·라벨·색상을 바꾼다 (MEM-A07). 값은 서비스가 정리해서 넘긴다 — null 은 지우기다. */
    public void changeDisplay(String area, String roleLabel, String color) {
        this.area = area;
        this.roleLabel = roleLabel;
        this.color = color;
    }

    /** 팀장 위임 (MEM-A08). 프로젝트당 활성 팀장은 하나라(ux_member_one_leader) 내리는 쪽을 먼저 flush 해야 한다. */
    public void changeRole(ProjectRole role) {
        this.role = role;
    }

    /** 제외·나가기 (MEM-A09·A10). 행은 지우지 않는다 — 작업·이력이 멤버 ID 를 가리킨다. */
    public void end(MembershipEndReason reason, Instant now) {
        this.active = false;
        this.leftAt = now;
        this.leftReason = reason;
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

    public MembershipEndReason getLeftReason() {
        return leftReason;
    }
}
