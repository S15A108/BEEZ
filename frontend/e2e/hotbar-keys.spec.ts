import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

// 처음 들어올 때 뜨는 환영 창은 tour.spec.ts에서 따로 확인한다.
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

// S15P31A108-371 4번(D46): 업무 창이 열려 있어도 입력칸에 쓰는 중이 아니면 숫자 키로 메뉴를 바꾼다.
test('업무 창의 검색칸에서는 숫자가 글자로 들어가고, 칸 밖에서는 숫자 키로 메뉴를 바꾼다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await page.goto('/projects/101')
  await expect(page.locator('canvas')).toBeVisible()

  await page.keyboard.press('1')
  const dialog = page.getByRole('dialog')
  const search = dialog.getByLabel('검색어')
  await expect(search).toBeFocused()
  await page.keyboard.press('6')
  await expect(search).toHaveValue('6')
  await expect(page.getByRole('dialog', { name: '공용 문서함' })).toBeVisible()

  await dialog.getByRole('heading', { name: '공용 문서함' }).click()
  await page.keyboard.press('6')
  await expect(page.getByRole('dialog', { name: '대시보드' })).toBeVisible()
})
