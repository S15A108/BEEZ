import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

test.setTimeout(90_000)

// 처음 들어올 때 뜨는 환영 창은 tour.spec.ts에서 따로 확인한다.
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

async function login(page: Page) {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
}

async function openWorkspace(page: Page) {
  await login(page)
  await page.goto('/projects/101')
  await expect(
    page.getByRole('main', { name: '프로젝트 워크스페이스' }),
  ).toBeVisible()
  await expect(page.locator('canvas')).toBeVisible()
}

test('선택한 부지의 프로젝트와 외관이 새로고침 뒤에도 유지된다', async ({
  page,
}) => {
  const errors: string[] = []
  const externalRequests: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const url = new URL(request.url())
    // GLB 안의 텍스처는 three가 메모리 주소(blob:)로 읽는다. 네트워크 요청만 센다(사용자 승인 2026-11-03).
    if (url.protocol === 'blob:' || url.protocol === 'data:') return
    if (
      url.pathname.startsWith('/api/') ||
      !['localhost', '127.0.0.1'].includes(url.hostname)
    )
      externalRequests.push(request.url())
  })
  await login(page)
  await expect(page.locator('canvas')).toBeVisible()
  await page
    .getByRole('button', { name: '프로젝트 만들기', exact: true })
    .first()
    .click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('부지 3')
  await dialog.getByLabel('프로젝트 이름').fill('부지 이식 확인')
  await dialog.getByLabel('프로젝트 소개').fill('도시에서 만든 목 프로젝트')
  await dialog.getByText('목조 스튜디오').click()
  await dialog
    .getByRole('button', { name: '프로젝트 만들기', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '부지 이식 확인', exact: true }),
  ).toBeVisible()
  await expect(page.locator('canvas')).toBeVisible()
  await page.getByRole('button', { name: '1인칭', exact: true }).click()
  await page.getByRole('button', { name: '3인칭', exact: true }).click()
  await page.getByRole('button', { name: '산책', exact: true }).click()
  await expect(
    page.getByRole('button', { name: '사무실로', exact: true }),
  ).toBeVisible()
  // 거리는 늘 1인칭이라 산책 중에는 시점 버튼을 숨긴다.
  await expect(
    page.getByRole('button', { name: '3인칭', exact: true }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '사무실로', exact: true }).click()
  await expect(
    page.getByRole('button', { name: '3인칭', exact: true }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: '부지 이식 확인', exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '나의 도시로', exact: true }).click()
  await expect(page.getByRole('link', { name: /부지 이식 확인/ })).toBeVisible()
  const localState = await page.evaluate(() =>
    localStorage.getItem('beez.scene.preferences.v1'),
  )
  expect(localState).toContain('wood')
  expect(errors).toEqual([])
  expect(externalRequests).toEqual([])
  await page.screenshot({
    path: test.info().outputPath('city.png'),
    fullPage: true,
  })
})

test('문서와 작업 검증, 비비의 근거 응답을 같은 사무실에서 연다', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await openWorkspace(page)
  await page.getByRole('button', { name: '공용 문서함', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await expect(
    dialog.getByRole('heading', { name: '공용 문서함', exact: true }).first(),
  ).toBeVisible()
  await expect(
    dialog.getByRole('heading', { name: '현재 문서', exact: true }),
  ).toBeVisible()
  await expect(
    dialog.getByText('팀장이 승인해 지금 기준으로 쓰는 문서예요.'),
  ).toBeVisible()
  await dialog.getByLabel('검색어').fill('로그인')
  await dialog.getByRole('button', { name: '검색', exact: true }).click()
  await expect(dialog.getByRole('heading', { name: '검색 결과' })).toBeVisible()
  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await page.getByRole('button', { name: '작업 현황', exact: true }).click()
  dialog = page.getByRole('dialog')
  await expect(
    dialog.getByText('설계와 계획의 검증 결과', { exact: true }),
  ).toBeVisible()
  await expect(
    dialog.getByRole('button', { name: '작업 시작', exact: true }),
  ).toBeEnabled()
  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await page.getByRole('button', { name: '비비와 대화', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog
    .getByLabel('설계 질문')
    .fill('로그인 요구사항의 근거는 무엇인가요?')
  await dialog.getByRole('button', { name: '보내기' }).click()
  await expect(dialog.getByText('템플릿 응답', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('log')).toContainText('로그인 요구사항의 근거')
  expect(errors).toEqual([])
  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await page.screenshot({
    path: test.info().outputPath('office.png'),
    fullPage: true,
  })
})

test('발행한 제안에서 발행 후 분석 상태를 확인하고 발행 전 분석과 구분한다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '변경 제안', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(
    dialog.getByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ }),
  ).toBeVisible()
  await dialog.getByRole('button', { name: '승인하고 기준 v2 발행' }).click()
  // 승인 확인 화면은 승인하면 같이 바뀌는 것(CHG-A14)을 먼저 보여 준다.
  const preview = dialog.getByRole('group', {
    name: '승인하면 같이 바뀌는 것',
  })
  await expect(preview).toContainText('기준 v2에')
  await expect(
    preview.getByRole('region', { name: '확인하지 못한 것' }),
  ).toBeVisible()
  await dialog.getByRole('button', { name: '승인하고 기준 v2 발행' }).click()
  const status = dialog.getByRole('region', { name: '발행 후 분석 상태' })
  await expect(status).toContainText('상태 확인 필요')
  await expect(status).toContainText('발행 전 영향 분석과는 별도의 처리 상태')
  await dialog.getByRole('tab', { name: /^영향/ }).click()
  await expect(dialog.getByText(/발행 전 영향 분석 결과예요/)).toBeVisible()
  await expect(dialog.getByRole('button', { name: '다시 분석' })).toHaveCount(0)
  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await page.getByRole('button', { name: '대시보드', exact: true }).click()
  const dashboard = page.getByRole('dialog')
  await dashboard.getByRole('tab', { name: '활동 타임라인' }).click()
  await dashboard.getByRole('button', { name: '변경 요청 #71' }).first().click()
  await expect(
    page.getByRole('region', { name: '발행 후 분석 상태' }),
  ).toContainText('상태 확인 필요')
})

test('변경 제안의 활동 탭에서 행위자별로 거르고 더 보기로 이어 읽는다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '변경 제안', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('tab', { name: '활동' }).click()
  const list = dialog.getByRole('list').last()
  await expect(list.getByRole('listitem')).toHaveCount(10)
  await dialog.getByRole('button', { name: '더 보기' }).click()
  await expect(list.getByRole('listitem')).toHaveCount(12)
  await expect(dialog.getByRole('button', { name: '더 보기' })).toHaveCount(0)
  await dialog.getByRole('button', { name: '에이전트', exact: true }).click()
  await expect(list.getByRole('listitem')).toHaveCount(2)
  await expect(list).toContainText('민수 에이전트')
})

// S15P31A108-202: 기획 분석에서 질문에 답하고, 초안 생성이 만든 후보를 채택하면 설계 항목 초안이 된다(예시 API).
test('기획 분석에서 질문에 답하고 초안 후보를 채택한다', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await login(page)
  await page.goto('/projects/102')
  await expect(page.locator('canvas')).toBeVisible()
  // 기획 분석 창은 단축 바에 없어 개발 서버의 저장소로 연다.
  await page.evaluate(async () => {
    const path = '/src/stores/workspaceStore.ts'
    const store = await import(/* @vite-ignore */ path)
    store.useWorkspaceStore.getState().openPane('planning')
  })
  const dialog = page.getByRole('dialog')
  await dialog.getByText('프로젝트 삭제 권한은 인간 팀장만 가지나요?').click()
  await dialog.getByRole('textbox', { name: '답변' }).fill('팀장만 삭제해요.')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog.getByText('답변을 저장했어요.')).toBeVisible()
  await dialog.getByRole('button', { name: '답변으로 초안 만들기' }).click()
  const candidate = dialog
    .locator('article')
    .filter({ hasText: '새 항목' })
    .filter({ hasNotText: '근거 없음' })
    .first()
  await expect(candidate).toBeVisible({ timeout: 15_000 })
  await candidate.getByRole('button', { name: '채택' }).click()
  await expect(dialog.getByText('후보를 채택했어요.').first()).toBeVisible()
  expect(errors).toEqual([])
})

test('비비가 연 회의에서 대화하고 비비 정리를 받아 회의를 끝내면 변경 제안이 올라간다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '회의실', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '초대 안내 문구 검토', exact: true }),
  ).toBeVisible()
  // 회의는 사람이 만들지 않고, 참여 응답 버튼도 없다.
  await expect(
    page.getByRole('button', { name: '회의 만들기', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '참여하기', exact: true }),
  ).toHaveCount(0)
  const log = page.getByRole('log', { name: '회의 대화' })
  await page
    .getByRole('textbox', { name: /메시지/ })
    .fill('3D 부지 선택을 유지하고 API 계약을 프론트에서 준비합니다.')
  await page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect(
    log.getByText('3D 부지 선택을 유지하고 API 계약을 프론트에서 준비합니다.', {
      exact: true,
    }),
  ).toBeVisible()
  // 정리가 없으면 회의를 끝낼 수 없다.
  await expect(
    page.getByRole('button', { name: '회의 끝내기', exact: true }),
  ).toHaveCount(0)
  await page
    .getByRole('button', { name: '비비에게 정리 요청', exact: true })
    .click()
  await expect(log.getByRole('region', { name: '바뀔 문서' })).toBeVisible()
  // 새로 고쳐도 최신 정리를 서버(MTG-A15)에서 다시 받아 이어 간다.
  await page.reload()
  await page.getByRole('button', { name: '회의실', exact: true }).click()
  await expect(
    page
      .getByRole('log', { name: '회의 대화' })
      .getByRole('region', { name: '바뀔 문서' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '회의 끝내기', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '회의를 끝낼까요?' }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '회의 끝내고 변경 제안 만들기' })
    .click()
  await expect(
    page.getByRole('region', { name: '회의 끝낸 결과' }),
  ).toContainText('변경 제안을 올렸어요')
  // 끝난 회의는 읽기만 하고, 변경 제안으로 이동할 수 있다.
  await expect(
    page.getByText('끝난 회의라 메시지를 보낼 수 없어요.'),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '변경 제안 보기', exact: true }),
  ).toBeVisible()
})

// 이름표(HTML 버튼)는 캔버스 위에 겹쳐 있다. 이름표 위에서 굴린 휠과 이름표에서 시작한 끌기도 고리를 돌리고 페이지는
// 스크롤하지 않는다. 끌지 않고 누르면 이름표가 그대로 연다(preview 확인에서 찾은 버그, 2026-11-03).
test('도시 이름표 위에서도 휠과 끌기로 고리를 돌리고, 누르면 연다', async ({
  page,
}) => {
  await login(page)
  const tag = page
    .getByRole('group', { name: /돌리는 원형 도시/ })
    .getByRole('button', { name: /새 프로젝트/ })
  await expect(tag).toBeVisible()
  const x = async () => (await tag.boundingBox())!.x
  const start = await tag.boundingBox()
  await page.mouse.move(
    start!.x + start!.width / 2,
    start!.y + start!.height / 2,
  )
  await page.mouse.wheel(0, 250)
  await expect.poll(x).toBeLessThan(start!.x - 100)
  expect(await page.evaluate('window.scrollY')).toBe(0)
  const centred = (await tag.boundingBox())!
  const cx = centred.x + centred.width / 2
  const cy = centred.y + centred.height / 2
  await page.mouse.move(cx, cy)
  await page.mouse.down()
  // 빈 부지에서 프로젝트 쪽(오른쪽)으로 한 칸 끌면 고리가 돌아 빈 부지가 오른쪽으로 간다. 꾸밈 칸 쪽으로 끌면 가장
  // 가까운 멈춤 자리인 빈 부지로 돌아온다(꾸밈 칸에는 멈추지 않는다).
  await page.mouse.move(cx + 230, cy, { steps: 12 })
  await page.mouse.up()
  await expect.poll(x).toBeGreaterThan(centred.x + 100)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await tag.click()
  await expect(page.getByRole('dialog')).toContainText('부지 3')
})

// 목록 API에 없는 값은 올린 프로젝트만 불러 호버 카드로 보인다(D41). 배지는 호버 카드가 열려도 보인다.
test('프로젝트 카드에 올리거나 키보드로 들어가면 호버 카드가 열리고 배지가 보인다', async ({
  page,
}) => {
  await login(page)
  const running = page.getByRole('link', { name: /우리 팀 협업 서비스/ })
  const preparing = page.getByRole('link', { name: /동네 책방 예약/ })
  const tooltip = page.getByRole('tooltip')
  await expect(running).toHaveAccessibleName(/안 읽은 알림 2건/)
  await expect(running.getByText('2', { exact: true })).toBeVisible()
  await expect(preparing).toHaveAccessibleName('동네 책방 예약')
  await expect(tooltip).toHaveCount(0)

  await running.hover()
  await expect(tooltip).toContainText('진행 중 · 기준 v1')
  await expect(tooltip).toContainText('팀장')
  await expect(tooltip).toContainText('검토할 변경')
  await expect(tooltip).toContainText('승인 문서')
  await expect(tooltip).toContainText('안 읽은 알림 2건')
  await expect(running.getByText('2', { exact: true })).toBeVisible()
  const card = (await running.boundingBox())!
  const shown = (await tooltip.boundingBox())!
  // 올린 카드 위에 겹치고 폭은 카드에 맞춘다.
  expect(Math.abs(shown.x - (card.x - 6))).toBeLessThan(1)
  expect(Math.abs(shown.width - (card.width + 12))).toBeLessThan(1)

  await preparing.hover()
  await expect(tooltip).toHaveCount(1)
  await expect(tooltip).toContainText('시작 준비 중')
  await expect(tooltip).toContainText('문서 초안')
  await expect(tooltip).toContainText(/다음: /)
  await expect(tooltip).not.toContainText('안 읽은 알림')

  await page.mouse.move(0, 0)
  await expect(tooltip).toHaveCount(0)
  await page.getByLabel('정렬').focus()
  await page.keyboard.press('Tab')
  await expect(tooltip).toHaveCount(1)
  await page.keyboard.press('Shift+Tab')
  await expect(tooltip).toHaveCount(0)
})

test('지도 이름표에 올리면 호버 카드가 제목 아래 고정 자리에 열리고 키보드로도 열린다', async ({
  page,
}) => {
  await login(page)
  const city = page.getByRole('group', { name: /돌리는 원형 도시/ })
  const tag = city.getByRole('button', { name: /우리 팀 협업 서비스/ })
  const tooltip = page.getByRole('tooltip')
  await expect(tag).toBeVisible()
  await expect(tag).toHaveAccessibleName(/안 읽은 알림 2건/)
  await tag.hover()
  await expect(tooltip).toContainText('진행 중 · 기준 v1')
  const box = (await page
    .getByRole('region', { name: '내 프로젝트 도시' })
    .boundingBox())!
  const shown = (await tooltip.boundingBox())!
  expect(shown.width).toBe(272)
  expect(Math.round(shown.x - box.x)).toBeLessThanOrEqual(25)
  expect(Math.round(shown.y - box.y)).toBeLessThanOrEqual(69)
  await page.mouse.move(0, 0)
  await expect(tooltip).toHaveCount(0)
  // 이름표 아래 건물에 올려도 같은 카드가 열린다.
  const at = (await tag.boundingBox())!
  await page.mouse.move(at.x + at.width / 2, at.y + at.height + 40)
  await expect(tooltip).toContainText('진행 중 · 기준 v1')
  await page.mouse.move(0, 0)
  await expect(tooltip).toHaveCount(0)
  await tag.focus()
  await page.keyboard.press('Shift+Tab')
  await page.keyboard.press('Tab')
  await expect(tooltip).toHaveCount(1)
})

// 띄어쓰기 없는 긴 이름이 사무실 왼쪽 위 패널을 밀어내지 않고, 이름표와 카드도 제 폭 안에 머문다.
test('띄어쓰기 없는 긴 이름도 이름표·카드·사무실 패널을 깨뜨리지 않는다', async ({
  page,
}) => {
  const name = 'ㅁ'.repeat(40)
  await login(page)
  await page
    .getByRole('button', { name: '프로젝트 만들기', exact: true })
    .first()
    .click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('프로젝트 이름').fill(name)
  await dialog.getByLabel('프로젝트 소개').fill('ㄴ'.repeat(120))
  await dialog
    .getByRole('button', { name: '프로젝트 만들기', exact: true })
    .click()

  const panel = page.getByRole('region', { name: '프로젝트', exact: true })
  await expect(panel.getByRole('heading', { name })).toBeVisible()
  const outer = (await panel.boundingBox())!
  const title = (await panel.getByRole('heading', { name }).boundingBox())!
  expect(outer.width).toBe(320)
  expect(title.x + title.width).toBeLessThanOrEqual(outer.x + outer.width)

  await page.getByRole('button', { name: '나의 도시로', exact: true }).click()
  const tag = page
    .getByRole('group', { name: /돌리는 원형 도시/ })
    .getByRole('button', { name })
  await expect(tag).toBeVisible()
  expect((await tag.boundingBox())!.width).toBeLessThanOrEqual(200)
  const card = page.getByRole('link', { name })
  // 설명은 한 줄로 잘리지 않고 줄을 바꿔 두 줄(19px씩)에서 끝나고, 카드 밖으로 나가지 않는다.
  const description = card.getByText('ㄴ'.repeat(120))
  expect((await description.boundingBox())!.height).toBe(38)
  expect(
    await card.evaluate(
      (element) => element.scrollWidth <= element.clientWidth,
    ),
  ).toBe(true)
})

// 사용자 요청 '가장 마지막에 방문한 프로젝트 건물이 가운데로'(D33): 1번 칸 프로젝트에 들어갔다 나오면 그 건물이 가운데에 온다.
test('마지막으로 연 프로젝트 건물이 도시 가운데에 온다', async ({ page }) => {
  await login(page)
  const city = page.getByRole('group', { name: /돌리는 원형 도시/ })
  const tag = city.getByRole('button', { name: /동네 책방 예약/ })
  const centre = async () => {
    const box = (await city.boundingBox())!
    const at = (await tag.boundingBox())!
    return Math.abs(at.x + at.width / 2 - (box.x + box.width / 2))
  }
  // 처음에는 가장 최근 칸(우리 팀 협업 서비스)이 가운데라 1번은 왼쪽에 있다
  await expect(tag).toBeVisible()
  expect(await centre()).toBeGreaterThan(100)
  await page.goto('/projects/102')
  await expect(page.locator('canvas')).toBeVisible()
  await page.goto('/')
  await expect(tag).toBeVisible()
  await expect.poll(centre).toBeLessThan(30)
})

// S15P31A108-285: 팀장 시작 가이드 1단계에 에이전트 연결이 없었다(SCR-06-v2-workspace-preparing, -docs-ready).
test('팀장 시작 가이드 1단계에서 초대와 에이전트 연결을 하고, 건너뛰면 링크로 다시 간다', async ({
  page,
}) => {
  await login(page)
  await page
    .getByRole('button', { name: '프로젝트 만들기', exact: true })
    .first()
    .click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('프로젝트 이름').fill('가이드 확인')
  await dialog.getByLabel('프로젝트 소개').fill('시작 가이드 1단계')
  await dialog
    .getByRole('button', { name: '프로젝트 만들기', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '가이드 확인', exact: true }),
  ).toBeVisible()
  const setup = page.getByRole('list', { name: '팀원 초대와 에이전트 연결' })
  await setup.getByRole('button', { name: '내 에이전트 연결 연결하기' }).click()
  await expect(
    page.getByRole('heading', { name: 'Claude Code에 BEEZ 추가' }),
  ).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(
    setup.getByRole('button', { name: '팀원 초대 초대하기' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '나중에 할게요' }).click()
  await expect(setup).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '지금 초대하기' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '에이전트 연결하기' }).click()
  await expect(
    page.getByRole('heading', { name: 'Claude Code에 BEEZ 추가' }),
  ).toBeVisible()
})

// S15P31A108-285: 창이 검색칸에 포커스를 주면서 창을 연 숫자가 검색어로 들어갔다.
test('숫자 키 1로 공용 문서함을 열어도 검색칸이 비어 있다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.keyboard.press('1')
  const search = page.getByLabel('검색어')
  await expect(search).toBeVisible()
  await expect(search).toHaveValue('')
})

// S15P31A108-364: 창이 '승인된 문서' 칩에 포커스를 줘서 비비를 열자마자 친 질문이 들어가지 않고 Enter도 보내지 않았다.
test('단축 바로 비비를 열고 바로 질문을 치고 Enter를 누르면 보내진다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '비비와 대화', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('설계 질문')).toBeFocused()
  await page.keyboard.type('로그인 요구사항의 근거는 무엇인가요?')
  await page.keyboard.press('Enter')
  await expect(dialog.getByRole('log')).toContainText('로그인 요구사항의 근거')
})

// S15P31A108-285: 2단계 명령 줄이 칸을 밀어내 '복사' 버튼이 창 밖으로 나갔다. 레이아웃이라 브라우저에서만 잡힌다.
test('설정 › 에이전트의 긴 명령 줄에서도 복사 버튼이 창 안에 있다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '설정', exact: true }).click()
  await page
    .getByRole('navigation', { name: '설정 메뉴' })
    .getByRole('button', { name: /에이전트/ })
    .click()
  const copy = page.getByRole('button', { name: 'MCP 주소 복사' })
  await expect(copy).toBeVisible()
  const dialog = (await page.getByRole('dialog').boundingBox())!
  const button = (await copy.boundingBox())!
  expect(button.x + button.width).toBeLessThanOrEqual(dialog.x + dialog.width)
})

// S15P31A108-338: 도움말은 새 탭으로 열어 사무실과 설정 창(한 번만 보이는 토큰)을 그대로 둔다.
// PR #230 리뷰: 라우트가 lazy라 새 탭 첫 로드에서 해시 절로 내려가지 않았다. 절이 화면 안에 있는지까지 본다.
test('설정 › 에이전트의 연결 실패 도움말 링크로 새 탭의 문제 해결 절에 바로 가고 연결 단계도 본다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '설정', exact: true }).click()
  await page
    .getByRole('navigation', { name: '설정 메뉴' })
    .getByRole('button', { name: /에이전트/ })
    .click()
  await page.getByRole('button', { name: '연결 확인' }).click()
  await expect(
    page.getByRole('status').filter({ hasText: '연결 실패' }),
  ).toBeVisible()
  const [help] = await Promise.all([
    page.waitForEvent('popup'),
    page.getByRole('link', { name: '연결 도움말' }).click(),
  ])
  await expect(help).toHaveURL(/\/help\/agent-connection#troubleshooting$/)
  const errors = help.getByRole('region', { name: '문제 해결' })
  await expect(errors).toBeInViewport()
  for (const step of [
    '1. 토큰 발급 (웹)',
    '2. Claude Code에 BEEZ 추가 (터미널)',
    '3. CLAUDE.md에 작업 절차 붙여넣기',
    '4. Claude Code 켜고 연결 확인',
    '5. 연결 해제',
  ])
    await expect(help.getByRole('heading', { name: step })).toBeVisible()
  for (const code of [
    'unauthenticated',
    'token_revoked',
    'agent_session_replaced',
    'not_found',
  ])
    await expect(errors.getByText(code, { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog')).toBeVisible()
})

// 캔버스 v491 문서 수정 제안: 문서함에서 '수정 제안하기' → ① 고친 문서 등록 → ② 변경점 확인 → ③ 올리기 전 확인 → 팀장에게 올리기.
test('문서 수정 제안을 세 단계로 등록하고 마지막에 팀장에게 올린다', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const shot = (name: string) =>
    page.screenshot({ path: test.info().outputPath(`propose-${name}.png`) })
  await openWorkspace(page)
  await page.getByRole('button', { name: '공용 문서함', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog
    .getByRole('button', { name: /요구사항 명세서/ })
    .first()
    .click()
  await dialog.getByRole('button', { name: '수정 제안하기' }).click()
  const steps = dialog.getByRole('list', { name: '문서 수정 제안 단계' })
  await expect(steps).toBeVisible()
  const text = dialog.getByLabel('고친 글')
  await expect(text).toHaveValue(/REQ-001/)
  await dialog.getByLabel('제목 *').fill('프로젝트 목록 빈 화면 안내')
  await dialog
    .getByLabel('바꾸려는 이유 *')
    .fill('참여한 프로젝트가 없는 사람은 빈 목록에서 멈춰요.')
  await text.press('ControlOrMeta+End')
  await text.pressSequentially(
    '\nREQ-006 참여한 프로젝트가 없으면 받은 초대를 먼저 보여 준다.',
  )
  await shot('1-doc')
  await dialog.getByRole('button', { name: '등록하고 변경점 찾기' }).click()
  await expect(
    dialog.getByRole('heading', { name: '바뀐 곳 1개를 찾았어요' }),
  ).toBeVisible()
  await shot('2-changes')
  await dialog.getByRole('button', { name: '다음', exact: true }).click()
  await expect(
    dialog.getByRole('heading', { name: '올리기 전에 확인해요' }),
  ).toBeVisible()
  await shot('3-confirm')
  await dialog.getByRole('button', { name: '팀장에게 올리기' }).click()
  await expect(dialog.getByText('변경 제안을 올렸어요.')).toBeVisible()
  await expect(
    dialog.getByRole('heading', { name: /프로젝트 목록 빈 화면 안내/ }),
  ).toBeVisible()
  await shot('4-mine')
  expect(errors).toEqual([])
})
