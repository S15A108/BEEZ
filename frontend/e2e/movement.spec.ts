import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

test.setTimeout(90_000)

// 처음 들어올 때 뜨는 환영 창은 tour.spec.ts에서 따로 확인한다.
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

async function office(page: Page, projectId: string) {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: /님의 도시/ })).toBeVisible()
  await page.goto(`/projects/${projectId}`)
  const canvas = page.locator('canvas')
  await expect(canvas).toHaveAttribute('data-player-x', /\d/)
  return canvas
}

async function walkRight(page: Page) {
  const canvas = page.locator('canvas')
  const startX = Number(await canvas.getAttribute('data-player-x'))
  await page.keyboard.down('d')
  try {
    await expect
      .poll(async () => Number(await canvas.getAttribute('data-player-x')))
      .toBeGreaterThan(startX + 3)
  } finally {
    await page.keyboard.up('d')
  }
}

test('시점 전환 버튼을 누른 직후 키보드로 캐릭터를 움직인다', async ({
  page,
}) => {
  const canvas = await office(page, '101')
  await page.getByRole('button', { name: '3인칭', exact: true }).click()
  await expect(canvas).toBeFocused()
  await walkRight(page)
  await page.getByRole('button', { name: '1인칭', exact: true }).click()
  await expect(canvas).toBeFocused()
  const position = async () =>
    `${await canvas.getAttribute('data-player-x')},${await canvas.getAttribute('data-player-y')}`
  const before = await position()
  await page.keyboard.down('w')
  try {
    await expect.poll(position).not.toBe(before)
  } finally {
    await page.keyboard.up('w')
  }
})

test('기준 설계가 없는 새 프로젝트에서도 걷고 문서 입력 중에는 멈춘다', async ({
  page,
}) => {
  const canvas = await office(page, '102')
  await canvas.focus()
  await walkRight(page)
  await page.getByRole('button', { name: '공용 문서함', exact: true }).click()
  const search = page.getByRole('dialog').getByLabel('검색어')
  await search.fill('wasd')
  const before = await canvas.getAttribute('data-player-x')
  await search.press('d')
  await expect(search).toBeFocused()
  await expect(canvas).toHaveAttribute('data-player-x', before!)
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '닫기', exact: true })
    .click()
  await expect(canvas).toBeFocused()
  await walkRight(page)
})
