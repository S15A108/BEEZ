import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

// S15P31A108-150: 롤백이 실패하면 서버가 리스 만료 뒤 실행을 회수해 ERROR(validation_lease_expired)로 닫는다
// (docs/design/11-validation-rollback.md 5절). 예시 모드 스위치로 그 장면을 만들고 결과와 재시도를 확인한다.
test('검증 실행이 회수되면 결과를 반영하지 않았다고 알리고, 재시도하면 다시 통과한다', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await page.goto('/projects/101')
  await expect(
    page.getByRole('main', { name: '프로젝트 워크스페이스' }),
  ).toBeVisible()
  await page.evaluate(() =>
    localStorage.setItem('beez.mock.validation', 'lease_expired'),
  )
  await page.getByRole('button', { name: '작업 현황', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: '다시 검증', exact: true }).click()
  await expect(
    dialog.getByText(
      '분석 실행이 제한 시간 안에 끝나지 않아 서버가 실행을 회수했어요. 이번 실행 결과는 반영하지 않았고 작업 상태도 그대로예요.',
    ),
  ).toBeVisible()
  await expect(
    dialog.getByRole('button', { name: '작업 시작', exact: true }),
  ).toBeDisabled()

  await page.evaluate(() => localStorage.removeItem('beez.mock.validation'))
  await dialog.getByRole('button', { name: '실행 재시도' }).click()
  await expect(
    dialog.getByRole('button', { name: '작업 시작', exact: true }),
  ).toBeEnabled()
  expect(errors).toEqual([])
})
