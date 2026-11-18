package com.beez.backend.designchange.service;

/**
 * 설계 발행 한 건이 "무엇을 바꿨고 어느 작업에 영향을 주는가"를 뽑는 공통 SQL 조각. WITH 절 안에 넣어 쓴다.
 * 필요한 바인딩: {@code :projectId}.
 *
 * <p><b>changed</b> — 기준 N 과 "바로 아래 번호" 기준의 baseline_item 비교. 번호가 이어진다고 가정하지 않는다.
 * <ul>
 *   <li>수정: 두 기준에 다 있는데 버전이 다름 → before·after 둘 다</li>
 *   <li>신규: N 에만 있음 → before 없음</li>
 *   <li>폐기: 직전 기준에만 있음 → after 없음</li>
 * </ul>
 * <b>발행(-194) 계약:</b> 폐기된 항목은 새 기준의 baseline_item 에서 <b>뺀다</b>. 이 조각과 시작 게이트 G2 는 둘 다
 * 스냅샷 비교만 하므로, 폐기 항목을 스냅샷에 남기고 design_item.retired_at 만 찍는 방식이면 둘 다 폐기를 못 잡는다.
 *
 * <p><b>affected</b> — 바뀐 항목의 <b>변경 전 버전 또는 그보다 옛 버전</b>을 <b>현재 계획</b>이 참조하는(READS·IMPLEMENTS·CHANGES 모두)
 * 작업. 버전 번호로 비교하므로 계획이 v1 을 참조할 때 v1→v2 와 v2→v3 발행이 모두 영향으로 잡힌다(리뷰 반영, -166).
 * 작업 상태로 거르지 않고 task_status 칸을 함께 준다. 쓰는 쪽이 거른다:
 * <ul>
 *   <li>get_design_changes 응답의 affected_task_ids 와 설계 발행 수신(-163)의 WAITING 대상 → COMPLETED·CANCELLED 제외</li>
 *   <li>작업을 지정한 변경 목록(task_id 필터, report_task_state) → 상태 무관 (완료·취소 보고에도 계획 이후 변경을 보여 준다)</li>
 * </ul>
 *
 * <p><b>-163 이 재사용하는 범위:</b> changed(바뀐 항목)와 affected(WAITING 대상)까지다. 검증을 STALE 로 바꿀 대상은
 * 여기서 뽑지 않는다 — REST CHG 발행 처리 규칙은 "변경 항목의 이전 버전을 validation_design_ref 에 가진 PASS·RUNNING 검증"
 * 이라 작업 상태나 현재 계획으로 거르지 않는다(완료된 작업·옛 리비전의 검증도 STALE). changed 의 (design_item_id, before_version_id)
 * 로 validation_design_ref 를 직접 찾는다.
 */
public final class DesignChangeQueries {

    private DesignChangeQueries() {
    }

    public static final String CHANGED_AND_AFFECTED = """
            changed as (
                select cur.id as baseline_id, cur.version_no, x.design_item_id, x.before_version_id, x.after_version_id
                  from baseline cur
                  cross join lateral (
                      select p.id
                        from baseline p
                       where p.project_id = cur.project_id
                         and p.version_no < cur.version_no
                       order by p.version_no desc
                       limit 1
                  ) prev
                  cross join lateral (
                      select ai.design_item_id,
                             pi.design_item_version_id as before_version_id,
                             ai.design_item_version_id as after_version_id
                        from baseline_item ai
                        left join baseline_item pi on pi.baseline_id = prev.id and pi.design_item_id = ai.design_item_id
                       where ai.baseline_id = cur.id
                         and (pi.design_item_version_id is null or pi.design_item_version_id <> ai.design_item_version_id)
                      union all
                      select pi.design_item_id, pi.design_item_version_id, null
                        from baseline_item pi
                       where pi.baseline_id = prev.id
                         and not exists (select 1 from baseline_item ai
                                          where ai.baseline_id = cur.id and ai.design_item_id = pi.design_item_id)
                  ) x
                 where cur.project_id = :projectId
            ),
            affected as (
                select c.baseline_id, t.id as task_id, t.status as task_status
                  from changed c
                  join design_item_version bv on bv.id = c.before_version_id
                  join task_plan_design_ref r on r.design_item_id = c.design_item_id
                  join design_item_version rv on rv.id = r.design_item_version_id
                  join task t on t.project_id = :projectId
                             and t.current_plan_revision_id = r.plan_revision_id
                 where rv.version_no <= bv.version_no
            )
            """;
}
