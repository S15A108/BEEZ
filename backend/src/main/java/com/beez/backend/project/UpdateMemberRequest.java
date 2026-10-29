package com.beez.backend.project;

/**
 * MEM-A07 요청. 생략(null)은 유지, 하나 이상 있어야 한다 (00-common 3절 PATCH). 빈 문자열 "" 은 지우기다 (PRJ-A04 description 과 같다).
 * 길이·색상 형식은 앞뒤 공백을 뺀 뒤 서비스에서 본다. 역할(LEADER/MEMBER)은 이 요청으로 바꾸지 않는다 — 위임(MEM-A08)으로만.
 */
public record UpdateMemberRequest(
        String domain,
        String roleLabel,
        String color
) {
}
