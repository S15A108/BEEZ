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
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
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

// S15P31A108-345: 시작한 프로젝트는 엘리베이터(산책 출구) 앞에서 시작해 첫 Enter가 비비 대신 산책으로 나갔다.
test('시작한 프로젝트 사무실에 막 들어와 Enter를 누르면 산책 대신 비비를 연다', async ({
  page,
}) => {
  const canvas = await office(page, '101')
  await canvas.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog').getByLabel('설계 질문')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '사무실로', exact: true }),
  ).toHaveCount(0)
})

// 산책 버그(2026-11-03): 고정 사무실(D36) 폭에서는 산책 시작점이 가로수 기둥과 겹쳐 어느 쪽으로도 걷지 못했다.
// 산책에서 W·D를 누르면 거리 위치가 바뀌어야 한다.
test('산책에서 키를 누르면 거리에서 걷는다', async ({ page }) => {
  const canvas = await office(page, '101')
  await page.getByRole('button', { name: '산책', exact: true }).click()
  // 거리 모델을 처음 만들 때는 몇 초 걸린다.
  await expect(canvas).toHaveAttribute('data-street-x', /\d/, {
    timeout: 30_000,
  })
  await canvas.focus()
  const at = async () => [
    Number(await canvas.getAttribute('data-street-x')),
    Number(await canvas.getAttribute('data-street-y')),
  ]
  for (const key of ['w', 'd']) {
    const [x, y] = await at()
    await page.keyboard.down(key)
    try {
      await expect
        .poll(async () => {
          const [nx, ny] = await at()
          return Math.hypot(nx - x, ny - y)
        })
        .toBeGreaterThan(8)
    } finally {
      await page.keyboard.up(key)
    }
  }
})
