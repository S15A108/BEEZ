import { expect, test } from '@playwright/test'

test.setTimeout(90_000)

test('워크스페이스에 처음 들어오면 환영 창이 한 번 뜨고 ? 키로 화면 안내를 다시 연다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
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

// S15P31A108-285: 강조 틀이 칸 높이 버튼을 감싸 80px 비비 원의 위가 어둡게 잘렸고, '6 비비' 설명이 단축 바 4~6칸을 가렸다.
test('둘러보기 6단계는 비비 원을 다 밝히고, 화면 안내의 비비 설명은 단축 바를 가리지 않는다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL('/')
  await page.goto('/projects/101')
  await page.getByRole('button', { name: '1분 둘러보기' }).click()
  for (let step = 0; step < 5; step++)
    await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page.getByText('화면 둘러보기 · 6 / 6')).toBeVisible()
  const circle = (await page.locator('[data-tour="bibi"]').boundingBox())!
  const hole = (await page.locator('div[style*="9999px"]').boundingBox())!
  expect(hole.y).toBeLessThanOrEqual(circle.y)
  expect(hole.y + hole.height).toBeGreaterThanOrEqual(circle.y + circle.height)
  await page.getByRole('button', { name: '건너뛰기' }).click()

  await page.getByRole('button', { name: '화면 안내 (?)' }).click()
  const note = (await page
    .getByText('설계가 궁금하면 물어보세요.')
    .locator('../..')
    .boundingBox())!
  for (const name of ['작업 현황', '설계 관계', '대시보드']) {
    const slot = (await page.getByRole('button', { name }).boundingBox())!
    expect(note.y + note.height).toBeLessThanOrEqual(slot.y)
  }
})

test('설정 링크로 처음 들어오면 설정 창만 열고 닫은 뒤에 환영 창을 보여 준다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await page.goto('/projects/101?settings=team')
  const settings = page.getByRole('dialog', { name: '설정' })
  await expect(settings).toBeVisible()
  const welcome = page.getByRole('dialog', {
    name: 'BEEZ 사무실에 오신 걸 환영해요',
  })
  await expect(welcome).toHaveCount(0)
  await settings.getByRole('button', { name: '닫기' }).click()
  await expect(settings).toHaveCount(0)
  await expect(welcome).toBeVisible()
})
