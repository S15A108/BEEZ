import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
  // 예시 사용량은 날짜에서 만들어져 오늘에 따라 달라진다. 시각을 고정한다(한국시간 11월 6일 15시).
  await page.clock.setFixedTime(new Date('2026-11-06T06:00:00Z'))
})

test('대시보드 네 탭에서 기간, 날짜별 호출, 보기 필터, 에이전트별 사용량을 본다', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await page.goto('/projects/101')
  await page.getByRole('button', { name: '대시보드', exact: true }).click()
  const dashboard = page.getByRole('dialog')

  // 한눈에 보기: 기간 드롭다운과 날짜별 그래프. 날짜 입력은 여기에 없다.
  const period = dashboard.getByRole('combobox', { name: '기간' })
  await expect(period).toHaveValue('this_month')
  await expect(
    dashboard.getByText('11월 1일~6일', { exact: true }),
  ).toBeVisible()
  await expect(dashboard.getByText(/날짜별 AI 호출\. 합계/)).toBeAttached()
  await expect(dashboard.getByLabel('시작일 (한국시간)')).toHaveCount(0)
  await period.selectOption('last_month')
  await expect(
    dashboard.getByText('10월 1일~31일', { exact: true }),
  ).toBeVisible()

  // 활동 타임라인: 보기 필터와 이어 붙이기
  await dashboard.getByRole('tab', { name: '활동 타임라인' }).click()
  const rows = dashboard.locator('section ol > li')
  await expect(rows).toHaveCount(20)
  await dashboard.getByRole('button', { name: '이전 활동 더 보기' }).click()
  // 20 + 17: 프로젝트 101 의 시드 활동 25건에 제안 71 이력(303) 12건이 더해져 37건이다.
  await expect(rows).toHaveCount(37)
  await expect(
    dashboard.getByRole('button', { name: '이전 활동 더 보기' }),
  ).toHaveCount(0)
  const filters = dashboard.getByRole('group', { name: '보기' })
  await filters.getByRole('button', { name: '김동료의 에이전트' }).click()
  await expect(rows.first()).toContainText('김동료의 에이전트')
  await expect(dashboard.getByText('이팀장', { exact: true })).toHaveCount(0)
  await filters.getByRole('button', { name: '사람' }).click()
  await expect(
    dashboard.getByText('이팀장', { exact: true }).first(),
  ).toBeVisible()
  await expect(
    dashboard.locator('section').getByText('김동료의 에이전트'),
  ).toHaveCount(0)

  // 에이전트 현황: 작업 제목, 보고 시각, 이번 달 사용량, 웹 직접 호출
  await dashboard.getByRole('tab', { name: '에이전트 현황' }).click()
  await expect(dashboard.getByText('이번 달 사용량').first()).toBeVisible()
  await expect(
    dashboard.getByText(/연결됨 · 10월 28일 보고/).first(),
  ).toBeVisible()
  await expect(dashboard.getByText('초대 화면 정보 바꾸기')).toBeVisible()
  await expect(dashboard.getByText('웹에서 직접 한 호출')).toBeVisible()

  // 사용량: 첫 보기는 에이전트별, 날짜 직접 입력은 여기에만
  await dashboard.getByRole('tab', { name: '사용량·비용' }).click()
  await expect(
    dashboard.getByRole('tab', { name: '에이전트별' }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(
    dashboard.getByText('웹에서 직접', { exact: true }),
  ).toBeVisible()
  await dashboard.getByLabel('시작일 (한국시간)').fill('2026-09-01')
  await dashboard.getByLabel('종료일 (한국시간)').fill('2026-09-30')
  await dashboard.getByRole('button', { name: '기간 적용' }).click()
  await expect(dashboard.getByText('기록된 사용량이 없어요.')).toBeVisible()

  // 호출이 없는 기간이면 그래프도 비어 있다고 보인다.
  await dashboard.getByRole('tab', { name: '한눈에 보기' }).click()
  await expect(period).toHaveValue('custom')
  await expect(
    dashboard.getByText('9월 1일~30일', { exact: true }),
  ).toBeVisible()
  await expect(
    dashboard.getByText('이 기간에 기록된 AI 호출이 없어요.').first(),
  ).toBeVisible()
  expect(errors).toEqual([])
})
