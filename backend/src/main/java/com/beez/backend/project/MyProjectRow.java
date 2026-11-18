package com.beez.backend.project;

/** 참여 프로젝트 목록 한 줄: 프로젝트와 조회자의 역할 (ProjectRepository#findMyProjects) */
public record MyProjectRow(Project project, ProjectRole role) {
}
