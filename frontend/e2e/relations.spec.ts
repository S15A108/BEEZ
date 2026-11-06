import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

test('설계 관계에서 고른 작업과 이름 붙은 선을 보고 작업 현황으로 간다', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: /님의 도시/ })).toBeVisible()
  await page.goto('/projects/101')
  await expect(page.locator('canvas')).toBeVisible()

  await page.getByRole('button', { name: '설계 관계', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: /TASK-002/ }).click()
  await expect(
    dialog.getByRole('heading', { name: /초대 화면 정보 바꾸기/ }),
  ).toBeVisible()

  const graph = dialog.getByRole('img', { name: /관계 그래프/ })
  await expect(graph.getByText('구현', { exact: true })).toBeVisible()
  await expect(graph.getByText('후보', { exact: true })).toBeVisible()
  await expect(graph).toHaveAccessibleName(/후보, 확정 아님/)

  await dialog.getByRole('button', { name: '작업 현황에서 보기' }).click()
  dialog = page.getByRole('dialog')
  await expect(
    dialog.getByText('설계와 계획의 검증 결과', { exact: true }),
  ).toBeVisible()
  expect(errors).toEqual([])
})
