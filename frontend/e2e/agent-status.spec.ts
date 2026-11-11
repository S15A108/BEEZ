import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

// S15P31A108-341: 화면마다 연결 상태를 따로 정해 같은 에이전트가 메뉴·팀 목록은 '연결 미확인', 사무실은 '연결 전'이었다.
// 이제 모두 연결 현황(AGT-A05) 하나로 정한다. 예시 데이터는 내 에이전트·최팀원 연결 끊김, 박팀원 연결 미확인, 김동료 연결됨이다.
// 사무실 이름표는 캔버스라 office-actors.test.ts가 확인한다.
test('끊긴 에이전트는 HUD·설정 메뉴·에이전트 화면·팀 목록·대시보드에서 모두 연결 끊김이다', async ({
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

  const hud = page.getByRole('list', { name: '팀 목록' })
  const row = (name: string) =>
    hud.getByRole('listitem').filter({ hasText: name })
  await expect(row('이팀장의 에이전트')).toContainText(
    '연결 끊김 · 마지막 통신',
  )
  await expect(row('박팀원의 에이전트')).toContainText('연결 미확인')
  await expect(row('김동료의 에이전트')).toContainText('연결됨')
  // 다른 팀원의 끊긴 에이전트는 사무실처럼 HUD에도 그리지 않는다.
  await expect(row('최팀원의 에이전트')).toHaveCount(0)

  await page.getByRole('button', { name: '설정', exact: true }).click()
  const menu = page.getByRole('navigation', { name: '설정 메뉴' })
  const agentItem = menu.getByRole('button', { name: /에이전트/ })
  await agentItem.click()
  await expect(agentItem).toContainText('연결 끊김')
  const status = page.getByRole('status')
  await expect(status).toHaveText(/^연결 끊김/)
  await expect(status).toContainText('마지막 통신')
  // 메뉴 이름 변경(S15P31A108-376): '팀' → '팀 관리'
  await menu.getByRole('button', { name: '팀 관리', exact: true }).click()
  const settings = page.getByRole('dialog')
  await expect(
    settings.getByText('에이전트 연결 끊김', { exact: true }),
  ).toHaveCount(2)
  await expect(
    settings.getByText('에이전트 연결 미확인', { exact: true }),
  ).toHaveCount(1)
  await expect(
    settings.getByText('에이전트 연결됨', { exact: true }),
  ).toHaveCount(1)
  await page.keyboard.press('Escape')
  await expect(settings).toHaveCount(0)

  // 대시보드는 연결됨만 센다(연결 미확인·끊김은 빼고). 아래 줄은 팀원 목록 순서(번호 내림차순)의 첫 미연결이다.
  await page.getByRole('button', { name: '대시보드', exact: true }).click()
  const metric = page
    .getByRole('dialog')
    .getByRole('button', { name: /^에이전트 연결/ })
  await expect(metric).toContainText('1 / 4')
  await expect(metric).toContainText('박팀원의 에이전트 연결 미확인')
  expect(errors).toEqual([])
})
