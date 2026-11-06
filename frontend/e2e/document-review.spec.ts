import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
})

// 기준 v1 전인 프로젝트 102에서 문서 검토 흐름을 목 API로 따라간다(S15P31A108-202).
test('비비와 대화해 반영하고 팀장에게 요청한 뒤 취소하면 다시 제안 확인 단계로 돌아온다', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: /님의 도시/ })).toBeVisible()
  await page.goto('/projects/102')
  await expect(page.locator('canvas')).toBeVisible()
  await page.getByRole('button', { name: '공용 문서함', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('li', { hasText: '기획안' }).getByRole('button').click()
  await expect(
    dialog.getByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeVisible()

  // 제안 1에서 비비와 이야기하고, 비비가 낸 문장으로 반영한다.
  await dialog
    .getByRole('button', { name: '비비와 이야기하기' })
    .first()
    .click()
  await dialog
    .getByLabel('비비에게 보낼 말')
    .fill('목적을 더 짧게 쓸 수 있을까?')
  await dialog.getByRole('button', { name: '보내기' }).click()
  await dialog.getByRole('button', { name: '이 문장으로 반영' }).click()
  await expect(
    dialog.getByText('비비가 대화에서 낸 문장으로 반영했어요.'),
  ).toBeVisible()

  // 남은 제안은 넘기고 최종본에서 팀장에게 검토를 요청한다.
  while (await dialog.getByRole('button', { name: '넘기기' }).count())
    await dialog.getByRole('button', { name: '넘기기' }).first().click()
  await expect(
    dialog.getByRole('heading', { name: '최종본 확인' }),
  ).toBeVisible()
  await dialog.getByRole('button', { name: '팀장에게 검토 요청' }).click()
  await expect(dialog.getByText(/승인을 요청했어요/)).toBeVisible()

  // 팀장이 결정하기 전에 올린 사람이 요청을 거둔다. 이력에는 취소됨이 남는다.
  await dialog.getByRole('button', { name: '검토 요청 취소' }).click()
  await page.getByRole('button', { name: '요청 취소하기' }).click()
  await expect(
    dialog.getByText(/비비가 수정 제안 \d개를 남겼어요/),
  ).toBeVisible()
  await expect(
    dialog.getByRole('list', { name: '지난 검토 요청' }),
  ).toContainText('취소됨')
  expect(errors).toEqual([])
})
