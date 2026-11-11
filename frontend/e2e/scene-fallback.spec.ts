import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

test.setTimeout(90_000)

// 처음 들어올 때 뜨는 환영 창은 tour.spec.ts에서 따로 확인한다.
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

// Chromium이 화면 읽기 도구에 넘기는 접근성 트리의 알림 문구. <canvas> 안의 대체 내용도 여기에 들어간다
// (Playwright의 getByRole은 그리지 않는 canvas 안쪽을 숨김으로 보아 잡지 못한다).
type AXNode = {
  nodeId: string
  role?: { value?: string }
  name?: { value?: string }
  childIds?: string[]
}
async function alertTexts(page: Page) {
  const cdp = await page.context().newCDPSession(page)
  const { nodes } = (await cdp.send('Accessibility.getFullAXTree')) as {
    nodes: AXNode[]
  }
  await cdp.detach()
  const byId = new Map(nodes.map((node) => [node.nodeId, node]))
  const text = (node?: AXNode): string =>
    [
      node?.name?.value ?? '',
      ...(node?.childIds ?? []).map((id) => text(byId.get(id))),
    ].join(' ')
  return nodes
    .filter((node) => node.role?.value === 'alert')
    .map(text)
    .join(' / ')
}

// 11-10 QA(S15P31A108-371): <canvas> 안의 대체 문구가 role="alert"라 3D가 잘 떠도 오류 알림으로 잡혔다.
test('3D 도시와 사무실이 뜨면 대체 문구를 오류 알림으로 내지 않는다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await expect(page.locator('canvas')).toBeVisible()
  expect(await alertTexts(page)).not.toContain('3D 지도')

  await page.goto('/projects/101')
  await expect(page.locator('canvas')).toHaveAttribute('data-player-x', /\d/)
  expect(await alertTexts(page)).not.toContain('3D 공간')
})
