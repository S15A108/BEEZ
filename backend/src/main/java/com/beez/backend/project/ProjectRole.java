package com.beez.backend.project;

/** 프로젝트 역할 (project_member.role). LEADER 는 MEMBER 권한을 포함한다. */
public enum ProjectRole {
    LEADER,
    MEMBER;

    /** 이 역할이 required 이상인가. */
    public boolean satisfies(ProjectRole required) {
        return this == LEADER || required == MEMBER;
    }
}
