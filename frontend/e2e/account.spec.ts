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
    '영문과 숫자를 모두 넣어 8자 이상으로 입력하세요. 숫자가 없어요.',
  )
})

// S15P31A108-266: 가입 이메일 인증(ACC-A11·A12)을 거쳐 verified_token으로 가입한다. 목 모드는 예시 코드를 화면에 보여 준다.
test('이메일 인증 코드를 받아 확인하면 가입하고 로그인 화면으로 간다', async ({
  page,
}) => {
  await page.goto('/register')
  await page.getByRole('textbox', { name: '이름', exact: true }).fill('새 팀원')
  await page
    .getByRole('textbox', { name: '이메일', exact: true })
    .fill('verify-e2e@beez.local')
  await page.getByRole('button', { name: '인증 코드 받기' }).click()
  await expect(page.getByText(/남은 시간 0[45]:\d\d/)).toBeVisible()
  const hint = await page.getByText(/예시 코드: \d{6}/).textContent()
  const code = hint!.slice(-6)

  await page
    .getByRole('textbox', { name: '인증 코드' })
    .fill(code === '000000' ? '111111' : '000000')
  await page.getByRole('button', { name: '확인', exact: true }).click()
  await expect(
    page.getByText('코드가 맞지 않아요 (남은 시도 4번)'),
  ).toBeVisible()
  await page.getByRole('textbox', { name: '인증 코드' }).fill(code)
  await page.getByRole('button', { name: '확인', exact: true }).click()
  await expect(page.getByText('인증됨')).toBeVisible()

  await page
    .getByRole('textbox', { name: '비밀번호', exact: true })
    .fill('beez-2026!')
  await page
    .getByRole('textbox', { name: '비밀번호 확인', exact: true })
    .fill('beez-2026!')
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(
    page.getByText('가입했어요. 등록한 계정으로 로그인해 주세요.'),
  ).toBeVisible()
})

// D48: 회원 설정은 페이지가 아니라 지금 화면 위에 뜨는 창이고, 상태는 주소의 ?account=에 있다.
test('나의 도시 위에 회원 설정 창을 열고 비밀번호 변경과 회원 탈퇴를 창 안 하위 화면으로 연다', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  // 회원 설정·로그아웃은 헤더의 이름 메뉴 안에 있다(S15P31A108-446).
  await page
    .getByRole('navigation', { name: '계정' })
    .getByRole('button')
    .click()
  await page.getByRole('menuitem', { name: '회원 설정' }).click()
  await expect(page.getByRole('dialog', { name: '회원 설정' })).toBeVisible()
  await expect(page).toHaveURL(/\/\?account=profile$/)
  await page.getByRole('button', { name: '비밀번호 변경' }).click()
  await expect(page).toHaveURL(/\/\?account=password$/)
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
  await expect(page).toHaveURL(/\/\?account=profile$/)
  await expect(page.getByText(/비밀번호를 바꿨어요/)).toBeVisible()
  await page.getByRole('button', { name: '회원 탈퇴' }).click()
  await expect(page).toHaveURL(/\/\?account=withdraw$/)
  await expect(
    page.getByText('이 프로젝트 때문에 지금은 탈퇴할 수 없어요'),
  ).toBeVisible()
  // 막힌 동안에는 쓸 수 없는 확인 폼 대신 해결 길만 보인다(S15P31A108-448).
  await expect(
    page.getByRole('link', { name: '팀장 위임' }).first(),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: '회원 탈퇴' })).toHaveCount(0)
  await expect(page.getByLabel('비밀번호 확인')).toHaveCount(0)
  // 하위 화면에서 Esc는 회원 설정으로, 한 번 더 누르면 창을 닫고 나의 도시가 그대로 있다.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '회원 설정' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page).toHaveURL(/\/$/)
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
})

test('예전 회원 설정 주소는 로그인한 뒤 나의 도시 위에 그 창을 연다', async ({
  page,
}) => {
  await page.goto('/account/withdraw')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/\?account=withdraw$/)
  await expect(page.getByRole('dialog', { name: '회원 탈퇴' })).toBeVisible()
})

test('사무실 설정 창에서 회원 설정을 열면 프로젝트 주소를 두고 위에 뜨고 단축 바 숫자 키는 먹지 않는다', async ({
  page,
}) => {
  test.setTimeout(90_000)
  await page.addInitScript(() => localStorage.setItem('beez.tour.11', 'seen'))
  await page.goto('/login')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: /님의 프로젝트/ }),
  ).toBeVisible()
  await page.goto('/projects/101')
  await expect(page.locator('canvas')).toBeVisible()
  await page.getByRole('button', { name: '설정', exact: true }).click()
  await page
    .getByRole('navigation', { name: '설정 메뉴' })
    .getByRole('link', { name: '회원 설정' })
    .click()
  await expect(page.getByRole('dialog', { name: '회원 설정' })).toBeVisible()
  await expect(page).toHaveURL(/\/projects\/101\?account=profile$/)
  // 설정 창은 닫혀 두 창이 겹치지 않는다.
  await expect(page.getByRole('navigation', { name: '설정 메뉴' })).toHaveCount(
    0,
  )
  await page.keyboard.press('1')
  await expect(page.getByRole('dialog')).toHaveCount(1)
  await page.getByRole('button', { name: '닫기' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page).toHaveURL(/\/projects\/101$/)
  await expect(page.locator('canvas')).toBeVisible()
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
