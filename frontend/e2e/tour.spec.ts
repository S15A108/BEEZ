import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test('워크스페이스에 처음 들어오면 환영 창이 한 번 뜨고 ? 키로 화면 안내를 다시 연다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: /님의 도시/ })).toBeVisible()
  await page.goto('/projects/101')
  const welcome = page.getByRole('dialog', {
    name: 'BEEZ 사무실에 오신 걸 환영해요',
  })
  await expect(welcome).toBeVisible()
  await expect(
    welcome.getByText('문서 초안 4종이 승인되면 프로젝트가 시작돼요'),
  ).toBeVisible()
  await welcome.getByRole('button', { name: '건너뛰고 바로 시작하기' }).click()
  await expect(welcome).toHaveCount(0)
  await page.reload()
  await expect(
    page.getByRole('main', { name: '프로젝트 워크스페이스' }),
  ).toBeVisible()
  await expect(
    page.getByRole('dialog', { name: 'BEEZ 사무실에 오신 걸 환영해요' }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '화면 안내 (?)' }).click()
  await expect(page.getByRole('dialog', { name: '화면 안내' })).toBeVisible()
})
