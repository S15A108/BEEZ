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
  await expect(page.getByRole('heading', { name: /님의 도시/ })).toBeVisible()
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
  await dialog.getByRole('button', { name: '비비에게 질문하기' }).click()
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
  await dashboard.getByRole('button', { name: '변경 요청 #71' }).click()
  await expect(
    page.getByRole('region', { name: '발행 후 분석 상태' }),
  ).toContainText('상태 확인 필요')
})

test('비비가 연 회의에서 대화하고 비비 정리를 목 API로 저장한다', async ({
  page,
}) => {
  await openWorkspace(page)
  await page.getByRole('button', { name: '회의실', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '초대 안내 문구 검토', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '회의 만들기', exact: true }),
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
  await page
    .getByRole('button', { name: '비비에게 정리 요청', exact: true })
    .click()
  await expect(page.getByRole('status')).toHaveText(
    '비비가 회의 내용을 정리했어요.',
  )
  await expect(log.getByText(/회의 내용을 정리했어요 · 메시지/)).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '회의실', exact: true }).click()
  await expect(
    page
      .getByRole('log', { name: '회의 대화' })
      .getByText('3D 부지 선택을 유지하고 API 계약을 프론트에서 준비합니다.', {
        exact: true,
      }),
  ).toBeVisible()
})
