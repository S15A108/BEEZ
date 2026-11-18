import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.12', 'seen'))
})

// 설계 기준 버전과 문서 버전은 따로 오른다. 숫자 앞에 무엇의 버전인지 붙여 '기준 v1'과 '기준 문서 v1'을 헷갈리지 않게 한다.
test('팀원 사무실의 프로젝트 카드와 시작 가이드는 버전 대상을 이름으로 보여 준다', async ({
  page,
}) => {
  await page.goto('/login')
  await page
    .getByRole('textbox', { name: '이메일', exact: true })
    .fill('member@beez.local')
  await page
    .getByRole('textbox', { name: '비밀번호', exact: true })
    .fill('beez-demo-2026')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await page.goto('/projects/101')
  await expect(page.locator('canvas')).toBeVisible()

  await expect(page.getByText(/· 설계 기준 v1$/)).toBeVisible()
  await expect(page.getByText('현재 승인 문서 읽기')).toBeVisible()
  await expect(page.getByText('기준 문서 v1 읽기')).toHaveCount(0)
})
