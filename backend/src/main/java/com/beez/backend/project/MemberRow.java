package com.beez.backend.project;

import com.beez.backend.account.Account;

/** 팀원 목록(MEM-A06) 한 행. 표시 이름이 계정에 있어 멤버십과 같이 읽는다. */
public record MemberRow(ProjectMember member, Account account) {
}
