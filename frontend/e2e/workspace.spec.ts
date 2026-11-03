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
  await page.getByRole('button', { name: '사무실로', exact: true }).click()
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
