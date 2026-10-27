package com.beez.backend.designchange.service;

/**
 * 설계 발행 한 건이 "무엇을 바꿨고 어느 작업에 영향을 주는가"를 뽑는 공통 SQL 조각. WITH 절 안에 넣어 쓴다.
 * 필요한 바인딩: {@code :projectId}.
 *
 * <p>쓰는 곳: get_design_changes(-164). 설계 발행 수신(-163)도 같은 조각으로 STALE 대상·WAITING 대상을 뽑아야
 * "영향 작업인데 검증은 유효" 같은 어긋남이 생기지 않는다 (REST CHG 발행 처리 규칙, 04 생명주기 S1).
 *
 * <p><b>changed</b> — 기준 N 과 "바로 아래 번호" 기준의 baseline_item 비교. 번호가 이어진다고 가정하지 않는다.
 * <ul>
 *   <li>수정: 두 기준에 다 있는데 버전이 다름 → before·after 둘 다</li>
 *   <li>신규: N 에만 있음 → before 없음</li>
 *   <li>폐기 (가): 직전 기준에만 있음 → after 없음</li>
 *   <li>폐기 (나): 두 기준에 같은 버전으로 있지만 design_item.retired_at 이 직전 발행 시각 이후·이번 발행 시각 이하 → after 없음.
 *       발행이 폐기 항목을 스냅샷에 남기고 표시만 하는 경우를 위한 것. 어느 쪽인지는 발행(-194) 확정 대기</li>
 * </ul>
 * <b>affected</b> — 바뀐 항목의 변경 전 버전을 <b>현재 계획</b>이 참조하는(READS·IMPLEMENTS·CHANGES 모두) 작업 중
 * COMPLETED·CANCELLED 가 아닌 것. CHANGES 로 그 항목을 제안한 작업도 포함한다 — REST CHG 발행 처리 규칙이
 * STALE 대상을 "이전 버전을 design_refs(READS·IMPLEMENTS·CHANGES)에 가진 검증"으로 정하고 있다.
 */
public final class DesignChangeQueries {

    private DesignChangeQueries() {
    }

    public static final String CHANGED_AND_AFFECTED = """
            changed as (
                select cur.id as baseline_id, cur.version_no, x.design_item_id, x.before_version_id, x.after_version_id
                  from baseline cur
                  cross join lateral (
                      select p.id, p.published_at
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
                      union all
                      select ai.design_item_id, ai.design_item_version_id, null
                        from baseline_item ai
                        join baseline_item pi on pi.baseline_id = prev.id and pi.design_item_id = ai.design_item_id
                        join design_item di on di.id = ai.design_item_id
                       where ai.baseline_id = cur.id
                         and pi.design_item_version_id = ai.design_item_version_id
                         and di.retired_at > prev.published_at
                         and di.retired_at <= cur.published_at
                  ) x
                 where cur.project_id = :projectId
            ),
            affected as (
                select c.baseline_id, t.id as task_id
                  from changed c
                  join task_plan_design_ref r on r.design_item_id = c.design_item_id
                                             and r.design_item_version_id = c.before_version_id
                  join task t on t.project_id = :projectId
                             and t.current_plan_revision_id = r.plan_revision_id
                             and t.status not in ('COMPLETED', 'CANCELLED')
            )
            """;
}
