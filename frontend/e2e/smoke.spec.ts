import { expect, test } from '@playwright/test'

test('첫 화면이 콘솔 에러 없이 뜬다', async ({ page }) => {
  const errors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })
  page.on('pageerror', (err) => errors.push(err.message))

  await page.goto('/')

  await expect(
    page.getByRole('heading', { name: 'BEEZ', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '로그인', exact: true }),
  ).toBeVisible()
  expect(errors).toEqual([])
})
