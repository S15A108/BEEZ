import { expect, test } from '@playwright/test'

test.describe('좁은 화면은 PC 이용 안내를 보여준다', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('앱 주소는 안내, 로그인은 열리고, 창을 키우면 돌아온다', async ({
    page,
  }) => {
    await page.goto('/')
    await expect(
      page.getByRole('heading', { name: 'PC에서 이용해 주세요' }),
    ).toBeVisible()
    await expect(page.locator('canvas')).toHaveCount(0)

    await page.goto('/login')
    await expect(
      page.getByRole('button', { name: '로그인', exact: true }),
    ).toBeVisible()

    await page.goto('/')
    await page.setViewportSize({ width: 1280, height: 800 })
    await expect(
      page.getByRole('heading', { name: 'PC에서 이용해 주세요' }),
    ).toHaveCount(0)
    await expect(
      page.getByRole('heading', { name: 'BEEZ', exact: true }),
    ).toBeVisible()
  })
})
