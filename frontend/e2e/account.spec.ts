import { expect, test } from '@playwright/test'

test('회원가입은 별도 페이지이고 비밀번호 규칙을 칸 아래에 알려 준다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('link', { name: '회원가입', exact: true }).click()
  await expect(page).toHaveURL(/\/register$/)
  await page.getByRole('textbox', { name: '이름', exact: true }).fill('새 팀원')
  await page
    .getByRole('textbox', { name: '이메일', exact: true })
    .fill('new@beez.local')
  await page
    .getByRole('textbox', { name: '비밀번호', exact: true })
    .fill('onlyletters')
  await page
    .getByRole('textbox', { name: '비밀번호 확인', exact: true })
    .fill('onlyletters')
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '영문, 숫자, 특수문자를 모두 넣어 8자 이상으로 입력하세요. 숫자, 특수문자가 없어요.',
  )
})

test('회원 설정에서 비밀번호 변경과 회원 탈퇴를 별도 페이지로 연다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: /님의 도시/ })).toBeVisible()
  await page.getByRole('link', { name: '회원 설정' }).click()
  await page.getByRole('link', { name: '비밀번호 변경' }).click()
  await expect(page).toHaveURL(/\/account\/password$/)
  await page
    .getByRole('textbox', { name: '현재 비밀번호', exact: true })
    .fill('beez-demo-2026')
  await page
    .getByRole('textbox', { name: '새 비밀번호', exact: true })
    .fill('beez-next-2026!')
  await page
    .getByRole('textbox', { name: '새 비밀번호 확인', exact: true })
    .fill('beez-next-2026!')
  await page.getByRole('button', { name: '비밀번호 변경' }).click()
  await expect(page).toHaveURL(/\/account$/)
  await expect(page.getByText(/비밀번호를 바꿨어요/)).toBeVisible()
  await page.getByRole('link', { name: '회원 탈퇴' }).click()
  await expect(page).toHaveURL(/\/account\/withdraw$/)
  await expect(
    page.getByText('이 프로젝트 때문에 지금은 탈퇴할 수 없어요'),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: '회원 탈퇴' })).toBeDisabled()
})

test('없는 주소와 유효하지 않은 초대는 같은 화면을 보여 준다', async ({
  page,
}) => {
  await page.goto('/no-such-page')
  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없어요' }),
  ).toBeVisible()
  await page.goto('/invite?token=does-not-exist')
  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없어요' }),
  ).toBeVisible()
})
