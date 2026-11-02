# frontend

S15A108 웹 클라이언트입니다. 이 문서의 규칙은 `frontend/` 안에만 적용됩니다. 브랜치, 커밋, PR 같은 팀 공통 규칙은 [팀 협업 컨벤션](../docs/collaboration-convention.md)을 따릅니다. 도구와 구조를 이렇게 정한 이유는 [결정 기록](docs/decisions.md)에 있습니다.

## 기술 스택

| 분류            | 사용                                                     | 버전        |
| --------------- | -------------------------------------------------------- | ----------- |
| 런타임          | Node.js                                                  | 24 (LTS)    |
| 패키지 매니저   | npm                                                      | 11          |
| 빌드 도구       | Vite                                                     | 8.3         |
| UI              | React                                                    | 19.3        |
| 언어            | TypeScript                                               | 6.0         |
| 라우터          | React Router                                             | 8.4         |
| HTTP            | axios                                                    | 1.20        |
| 서버 상태       | TanStack Query                                           | 5.104       |
| 클라이언트 상태 | Zustand                                                  | 5.0         |
| 폼              | React Hook Form (+ @hookform/resolvers)                  | 7.90        |
| 스키마 검증     | Zod                                                      | 4.6         |
| 스타일링        | Tailwind CSS (+ prettier-plugin-tailwindcss)             | 4.3         |
| UI 컴포넌트     | shadcn/ui (Radix, nova 프리셋) + lucide-react 아이콘     | 4.22 / 1.59 |
| 3D              | three / @react-three/fiber                               | 0.186 / 9.8 |
| Lint            | ESLint (+ typescript-eslint, react-hooks, react-refresh) | 10.13       |
| 포매터          | Prettier                                                 | 3.9         |
| 테스트          | Vitest + React Testing Library (jsdom)                   | 5.0 / 16.3  |
| E2E             | Playwright (Chromium)                                    | 1.64        |
| 배포            | 정적 파일 (`dist/`)                                      | -           |
| Storybook       | 도입하지 않음                                            | -           |

### 버전 관리 방식

- `package.json`에는 범위(`^`)가 적혀 있고, 실제 설치 버전은 `package-lock.json`이 고정합니다. lockfile은 반드시 커밋합니다.
- 설치는 `npm ci`로 합니다. `npm install`은 의존성을 추가하거나 올릴 때만 씁니다.
- Node 버전은 `package.json`의 `engines`(`>=24 <25`)와 `.npmrc`의 `engine-strict=true`로 강제합니다. Node 24가 아니면 설치가 실패합니다. CI에도 Node 24를 설치합니다.
- @react-three/fiber 9.8은 React `>=19 <19.4`만 지원합니다. React를 19.4 이상으로 올리기 전에 R3F 지원 여부를 먼저 확인합니다.
- `three`와 `@types/three`는 마이너 버전(0.186)을 맞춥니다.
- Playwright 브라우저는 `@playwright/test` 버전에 묶여 있습니다. 처음 설치하거나 Playwright를 올린 뒤에는 `npx playwright install chromium`을 한 번 실행합니다.
- `shadcn`은 컴포넌트를 추가하는 CLI라 devDependencies에 있습니다. 브라우저 번들에는 `shadcn/tailwind.css`만 들어갑니다.

## 명령어

```bash
npm ci                # 의존성 설치
npm run dev           # 개발 서버 (http://localhost:5173)
npm run lint          # ESLint
npm run format        # Prettier로 전체 포맷
npm run format:check  # 포맷 검사만 (CI용)
npm test              # 테스트 1회 실행
npm run test:watch    # 파일이 바뀔 때마다 테스트
npm run build         # 타입 체크(tsc -b) + 프로덕션 빌드 -> dist/
npm run check         # lint + 포맷 검사 + 테스트 + 빌드 (CI, PR 전 검사)
npm run test:e2e      # Playwright E2E (개발 서버를 자동으로 띄움)
npx shadcn add button # shadcn 컴포넌트 추가 -> src/components/ui/
npm run preview       # 빌드 결과 미리보기
```

개발 서버는 `/api`로 시작하는 요청을 `http://localhost:8080`(백엔드)으로 프록시합니다. 백엔드 CORS 설정 없이 개발할 수 있습니다.

## 디렉터리 구조

```
frontend/
├─ public/
│  └─ models/          # glTF/GLB 3D 모델 (URL로 로드)
├─ src/
│  ├─ main.tsx         # 엔트리. QueryClient, Router 연결
│  ├─ router.tsx       # 라우트 정의
│  ├─ App.tsx          # `/` 화면
│  ├─ index.css        # Tailwind 진입점, shadcn 테마 변수(:root, .dark)
│  ├─ test/setup.ts    # 테스트 공통 설정 (jest-dom, cleanup)
│  ├─ pages/           # 라우트 단위 화면
│  ├─ features/<도메인>/ # 도메인 기능. 컴포넌트, 훅, API 호출을 한 폴더에
│  ├─ components/      # 두 곳 이상에서 쓰는 공용 UI
│  │  └─ ui/           # shadcn 컴포넌트 (npx shadcn add로 생성)
│  ├─ stores/          # 여러 feature가 함께 쓰는 Zustand store
│  ├─ three/           # 3D 씬, 모델 컴포넌트, 셰이더
│  ├─ api/
│  │  └─ client.ts     # axios 인스턴스 (baseURL: /api)
│  ├─ lib/
│  │  └─ utils.ts      # cn() 클래스 병합 (shadcn)
│  └─ assets/          # import해서 쓰는 이미지, 폰트
├─ .claude/skills/     # Claude Code 스킬 (fe-feature, fe-api, fe-verify, fe-review, fe-commit-pr, three-scene)
├─ .vscode/            # 추천 확장, 저장 시 포맷
├─ CLAUDE.md           # Claude Code 작업 지침
├─ e2e/                # Playwright 테스트 (*.spec.ts)
├─ docs/
│  └─ decisions.md    # 결정 기록: 무엇을 왜 골랐고 왜 뺐는지
├─ playwright.config.ts
├─ components.json     # shadcn 설정
├─ eslint.config.js, .prettierrc
└─ .gitattributes, .editorconfig   # LF 줄바꿈, 들여쓰기
```

테스트 파일은 대상 파일 옆에 `Xxx.test.tsx`로 둡니다. 폴더는 첫 파일이 생길 때 만듭니다. 빈 폴더를 미리 만들지 않습니다. 한 feature에서만 쓰는 컴포넌트와 store는 공용 폴더로 올리지 않고 그 feature 폴더에 둡니다.

## 코드 컨벤션

### 파일과 이름

- 컴포넌트는 함수 컴포넌트로 작성합니다. 파일명은 PascalCase(`LoginForm.tsx`)이고 파일 하나에 컴포넌트 하나를 export합니다.
- 훅은 `useXxx.ts`, store는 `xxxStore.ts`(훅 이름은 `useXxxStore`), 그 밖의 파일은 camelCase(`formatDate.ts`)로 이름 짓습니다.
- 다른 폴더의 파일은 `@/` alias로 import합니다(`import { api } from '@/api/client'`). 같은 폴더 안에서는 상대 경로(`./`)를 씁니다.

### 타입

- TypeScript strict 모드입니다(TS 6 기본값). `any` 대신 `unknown`을 쓰고 좁혀서 사용합니다.
- 백엔드가 준비되면 API 응답 타입은 직접 쓰지 않고 OpenAPI 스펙에서 생성합니다.

### API 호출

- axios를 직접 import하지 않고 `@/api/client`의 `api` 인스턴스를 씁니다. baseURL, 인증 헤더, 공통 에러 처리는 이 인스턴스 한 곳에서 관리합니다.
- 경로는 `/api`를 뺀 나머지만 씁니다(`api.get('/users/me')`).

### 환경변수

- `.env`는 커밋하지 않습니다. 필요한 키 이름은 `.env.example`에 적어 커밋합니다.
- `VITE_`로 시작하는 변수는 빌드 결과물에 그대로 들어가 브라우저에서 보입니다. API 키 같은 시크릿은 넣지 않고, 시크릿이 필요한 호출은 백엔드를 거칩니다.

### 라우팅

- 라우트는 `src/router.tsx` 한 곳에 정의합니다. 화면 컴포넌트는 `pages/`에 두고 `XxxPage.tsx`로 이름 짓습니다.
- import는 `react-router`에서 합니다. `react-router-dom`은 쓰지 않습니다(v7부터 `react-router`로 합쳐졌습니다). `RouterProvider`만 `react-router/dom`에서 가져옵니다.
- 3D 페이지처럼 무거운 화면은 route의 `lazy`로 첫 화면 번들에서 분리합니다.

```tsx
{
  path: '/viewer',
  lazy: async () => ({ Component: (await import('@/pages/ViewerPage')).default }),
}
```

- 새로고침이나 URL 직접 접근은 개발 서버가 `index.html`로 돌려줍니다. 배포 서버에도 같은 설정이 필요합니다(아래 배포 섹션).

### 서버 상태 (TanStack Query)

- 서버에서 받아오는 데이터는 TanStack Query로 관리합니다. 응답을 Zustand나 `useState`에 복사하지 않습니다. 캐싱, 로딩, 에러, 재요청은 TanStack Query가 맡습니다.
- query는 훅으로 감싸 해당 feature 폴더에 둡니다. `queryFn`에서는 `api` 인스턴스로 요청하고, 응답은 Zod 스키마로 파싱해서 돌려줍니다.

```ts
// features/user/useMe.ts
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { api } from '@/api/client'

const userSchema = z.object({
  id: z.number(),
  name: z.string(),
})

export type User = z.infer<typeof userSchema>

export function useMe() {
  return useQuery({
    queryKey: ['users', 'me'],
    queryFn: () =>
      api.get('/users/me').then((res) => userSchema.parse(res.data)),
  })
}
```

- `queryKey`는 배열이고 첫 요소는 도메인 이름입니다(`['users', userId]`). 데이터를 바꾼 뒤(`useMutation`)에는 관련 key를 `invalidateQueries`로 무효화합니다.
- `QueryClient`는 기본 설정 그대로 씁니다. 실패하면 3번 재시도하고, 창에 다시 포커스하면 재요청합니다. 바꿔야 할 때는 `main.tsx`에서 고칩니다.

### 클라이언트 상태 (Zustand)

- 모달 열림, 선택한 탭처럼 서버와 상관없는 화면 상태가 대상입니다. 한 컴포넌트에서만 쓰면 `useState`로 충분하고, 여러 컴포넌트가 공유할 때만 Zustand store를 만듭니다.
- store에서는 필요한 값만 selector로 꺼냅니다(`useAuthStore((s) => s.user)`). store 전체를 꺼내면 관련 없는 값이 바뀔 때도 리렌더링됩니다.

### 데이터 검증 (Zod)

- TypeScript 타입은 컴파일할 때만 확인합니다. 백엔드가 실제로 다른 형태를 보내면 잡지 못하므로, API 응답은 Zod 스키마로 `parse`한 뒤 씁니다. 형태가 다르면 그 자리에서 에러가 나고 TanStack Query의 `error`로 전달됩니다.
- 타입은 직접 쓰지 않고 스키마에서 뽑습니다(`z.infer<typeof userSchema>`). 스키마와 타입이 어긋날 일이 없습니다.
- 스키마 이름은 `xxxSchema`이고, 쓰는 feature 폴더에 둡니다.

### 폼 (React Hook Form + Zod)

- 폼은 `useForm` + `zodResolver`로 만듭니다. 검증 규칙은 Zod 스키마에만 쓰고 `register`의 옵션(`required` 등)에는 쓰지 않습니다.
- 에러 메시지는 스키마에 한국어로 적고, 화면에는 `errors.<필드>.message`를 보여줍니다.
- 실제 화면의 입력 요소는 shadcn의 `Input`, `Label`, `Button`을 씁니다. 아래 예제는 RHF + Zod 패턴만 보이려고 기본 요소를 썼습니다.

```tsx
// features/auth/LoginForm.tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'

const loginSchema = z.object({
  email: z.email('이메일 형식이 아닙니다'),
  password: z.string().min(8, '8자 이상 입력하세요'),
})

type LoginInput = z.infer<typeof loginSchema>

type Props = { onSubmit: (data: LoginInput) => void }

export function LoginForm({ onSubmit }: Props) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({ resolver: zodResolver(loginSchema) })

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-2">
      <label>
        이메일
        <input {...register('email')} className="border" />
      </label>
      {errors.email && <p role="alert">{errors.email.message}</p>}
      <label>
        비밀번호
        <input type="password" {...register('password')} className="border" />
      </label>
      {errors.password && <p role="alert">{errors.password.message}</p>}
      <button type="submit">로그인</button>
    </form>
  )
}
```

### 스타일링 (Tailwind CSS)

- 스타일은 Tailwind 유틸리티 클래스로 씁니다. 컴포넌트별 CSS 파일은 만들지 않습니다.
- 색상, radius 같은 디자인 토큰은 `src/index.css`의 shadcn 테마 변수(`:root`, `.dark`)를 고쳐서 바꿉니다. 클래스는 토큰 이름으로 씁니다(`bg-primary`, `text-muted-foreground`). 임의 값(`bg-[#123456]`)은 토큰이 없을 때만 씁니다.
- 기본 폰트 Geist에는 한글 글리프가 없어서 한글은 시스템 폰트로 보입니다. 한글 폰트는 디자인이 정해지면 추가합니다.
- 클래스 순서는 Prettier 플러그인이 자동으로 정렬하므로 신경 쓰지 않아도 됩니다.
- Tailwind는 `<Canvas>` 바깥의 HTML UI에만 적용됩니다. 3D 씬 안의 색과 재질은 three.js에서 다룹니다.

### UI 컴포넌트 (shadcn/ui)

- 버튼, 입력, 다이얼로그 같은 UI는 직접 만들기 전에 shadcn에 있는지 먼저 확인하고 `npx shadcn add <이름>`으로 추가합니다. 생성된 파일은 `src/components/ui/`에 들어갑니다.
- `components/ui/`의 코드는 우리 코드입니다. 필요하면 고쳐도 되지만 모든 화면에 영향을 주므로 PR에 적습니다.
- 조건부 클래스나 variant는 `@/lib/utils`의 `cn()`으로 합칩니다. 아이콘은 `lucide-react`를 씁니다.

### 테스트 (Vitest + React Testing Library)

- 로직(유틸, store, 스키마)과 사용자 입력을 받는 컴포넌트(폼 등)를 추가하거나 고치면 테스트를 같이 씁니다.
- 테스트 이름은 한국어로 "무엇을 하면 어떻게 된다"처럼 씁니다.
- 요소는 사용자가 보는 기준으로 찾습니다(`getByRole`, `getByLabelText`, `getByText`). class 이름이나 DOM 구조로 찾지 않습니다.
- 사용자 동작은 `userEvent`로 흉내 냅니다.
- `describe`, `it`, `expect`, `vi`는 `vitest`에서 import합니다(전역 설정 없음).
- jsdom에는 WebGL이 없어서 `<Canvas>`가 들어간 컴포넌트는 렌더링 테스트를 하지 않습니다. 3D 쪽은 좌표 계산, 상태 변화 같은 로직을 함수로 분리해서 테스트합니다.

```tsx
// features/auth/LoginForm.test.tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { LoginForm } from './LoginForm'

it('잘못된 이메일이면 에러를 보여주고 제출하지 않는다', async () => {
  const onSubmit = vi.fn()
  render(<LoginForm onSubmit={onSubmit} />)

  await userEvent.type(screen.getByLabelText('이메일'), 'not-an-email')
  await userEvent.type(screen.getByLabelText('비밀번호'), 'password123')
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))

  expect(await screen.findByText('이메일 형식이 아닙니다')).toBeInTheDocument()
  expect(onSubmit).not.toHaveBeenCalled()
})

it('올바르게 입력하면 값을 제출한다', async () => {
  const onSubmit = vi.fn()
  render(<LoginForm onSubmit={onSubmit} />)

  await userEvent.type(screen.getByLabelText('이메일'), 'a@b.com')
  await userEvent.type(screen.getByLabelText('비밀번호'), 'password123')
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))

  expect(onSubmit).toHaveBeenCalledWith(
    { email: 'a@b.com', password: 'password123' },
    expect.anything(),
  )
})
```

### E2E 테스트 (Playwright)

- `e2e/*.spec.ts`에 둡니다. `npm run test:e2e`가 개발 서버를 띄우고 Chromium에서 실행합니다.
- 핵심 사용자 흐름(페이지 이동, 폼 제출, 3D 화면 진입)이 생기면 E2E를 추가합니다. 첫 화면, 부지 선택·생성·보존, 문서·작업·비비, 회의 기록을 확인합니다.
- 기본 E2E는 브라우저 예시 어댑터로 백엔드 없이 실행합니다. live 모드 경계를 따로 검증할 때는 API 응답을 `page.route`로 대체할 수 있습니다.

```ts
await page.route('**/api/users/me', (route) =>
  route.fulfill({ json: { id: 1, name: '홍길동' } }),
)
```

- 3D 화면은 픽셀 비교를 하지 않습니다. 캔버스가 보이는지와 콘솔 에러가 없는지까지만 확인합니다.

### 포맷과 검사

- Prettier 설정은 작은따옴표, 세미콜론 없음입니다. 나머지는 Prettier 기본값과 `.editorconfig`를 따릅니다.
- 줄바꿈은 LF입니다. `.gitattributes`가 강제하므로 Windows에서도 따로 설정할 필요가 없습니다.
- VS Code는 레포 루트가 아니라 `frontend/` 폴더를 열어서 작업합니다. 그래야 `.vscode/` 설정이 적용되어 추천 확장(ESLint, Prettier, EditorConfig, Tailwind CSS IntelliSense) 설치 안내가 뜨고, 저장할 때 자동으로 포맷됩니다.
- `npm run check`(lint, 포맷 검사, 테스트, 빌드)와 `npm run test:e2e`가 모두 통과해야 PR을 올릴 수 있습니다.

## 3D (three.js)

React Three Fiber(R3F)로 three.js를 React 컴포넌트처럼 씁니다. 씬을 추가하는 절차는 `/three-scene` 스킬에 있습니다.

- 3D 코드는 `src/three/`에 둡니다.
- three.js는 번들이 큽니다. `<Canvas>`를 쓰는 컴포넌트는 `React.lazy`로 불러와 첫 화면 번들에서 분리합니다.
- 모델 파일은 `public/models/`에 두고 `/models/xxx.glb` URL로 로드합니다. Vite는 `.glb`를 import 대상으로 인식하지 않습니다.
- 도시 화면과 사무실 창밖 건물은 Kenney City Kit CC0 에셋(`public/models/kenney/city`)이고, `License.txt`를 같이 둡니다. 읽기와 복제는 `src/three/kenney.ts`가 맡습니다([D21](docs/decisions.md)).
- 모델은 텍스처 해상도를 줄이고 압축한 뒤 커밋합니다. 10MB를 넘으면 팀과 상의해 Git LFS나 외부 스토리지를 씁니다.
- 매 프레임 바뀌는 값은 `useFrame` 안에서 ref로 직접 바꿉니다. 프레임마다 `setState`를 호출하면 React가 매번 리렌더링합니다.
- 움직임이 없는 씬은 `<Canvas frameloop="demand">`로 필요할 때만 렌더링합니다.
- 모델 로딩, 카메라 컨트롤 같은 헬퍼가 필요해지면 `@react-three/drei`를 추가합니다.

## 배포

프론트는 정적 파일이라 컨테이너 없이 배포합니다. CI는 다음 명령으로 검사와 빌드를 한 번에 합니다. 결과물은 `dist/`입니다.

```bash
npm ci
npm run check
```

`dist/`를 서빙하는 쪽(서버 nginx, 정적 호스팅)에는 두 가지 설정이 필요합니다. 배포 구성은 팀 공용 설정에서 정합니다.

- **SPA fallback**: 파일이 없는 경로는 `index.html`을 돌려줍니다. nginx라면 `try_files $uri $uri/ /index.html;`입니다. 없으면 새로고침할 때 404가 납니다.
- **`/api` 프록시**: `/api`로 시작하는 요청은 백엔드(8080)로 넘깁니다. 개발 서버의 proxy는 배포 환경에 적용되지 않습니다.

## gather 화면 이식 사용법

기존 Node 24, npm, 라우터, Query provider, axios, Zustand, Tailwind와 검사 설정을 유지하면서 gather의 도시·부지 선택·사무실·산책 화면을 옮겼습니다. 별도 의존성은 추가하지 않았습니다. 업무 화면은 shadcn 컴포넌트와 Tailwind로 구성하며, 기존 HTML 화면과 CSS를 통째로 포함하지 않습니다.

### 로컬 예시 환경과 실제 API 준비

환경변수를 지정하지 않으면 `VITE_API_MODE=mock`으로 실행합니다. `.env.example`에 모드 설정 예시가 있습니다.

- `mock`: axios 어댑터가 모든 요청을 브라우저 안에서 처리합니다. 실제 백엔드·AI·MCP 에이전트에는 연결하지 않습니다. 문서 업로드는 `.md`·`.markdown`·`.txt`를 지원하며, 분석은 실제 AI 호출이 아닌 로컬 예시 처리입니다. 로그인 화면의 예시 계정은 `demo@beez.local` / `beez-demo-2026`입니다. 예시 데이터는 이 브라우저의 localStorage에 저장됩니다.
- `live`: `.env.local`에 `VITE_API_MODE=live`를 설정하고 개발 서버를 다시 시작합니다. 같은 서비스 함수가 `/api` REST를 호출합니다. 개발 시 기존 Vite 프록시 대상은 `http://localhost:8080`입니다. `VITE_` 변수에 실제 토큰이나 AI 키를 넣지 않습니다.

현재 이식본은 실제 백엔드와 통합 검증하지 않았습니다. 예시 환경의 분석·승인·에이전트 연결 표시는 화면과 계약 흐름을 확인하는 로컬 데이터입니다. 실제 AI 결과, 외부 에이전트 접속, 메일 발송, 서버 영속화를 증명하지 않습니다.

백엔드 REST 명세의 108개 경로는 `src/api/contracts/endpoints.ts`에 등록했고, `src/api/services/`와 각 feature의 `schemas.ts`에 요청·응답 경계를 준비했습니다. 십진 문자열 ID, snake_case 필드, 선택 필드의 생략, 세션 쿠키 인증, UTC 시각을 그대로 따릅니다. 화면의 시각과 기간 입력은 한국시간으로 표시합니다. 실제 OpenAPI와 인증·CSRF 세부 계약이 확정되면 이 경계를 대조해야 합니다.

### 화면과 3D 코드 위치

| 위치                                         | 역할                                                                         |
| -------------------------------------------- | ---------------------------------------------------------------------------- |
| `src/pages/CityPage.tsx`                     | 프로젝트 도시, 부지 선택과 프로젝트 생성                                     |
| `src/pages/WorkspacePage.tsx`                | 사무실, 1·3인칭 전환, 산책과 업무 패널 진입                                  |
| `src/features/workspace/`                    | 워크스페이스 게임 UI: 프로젝트 카드, 시작 가이드, 단축 바, 설정 창           |
| `src/features/`                              | 문서, 기획 분석, 변경 제안, 작업 검증, 회의, 팀, 에이전트 연결, 활동·비용 등 |
| `src/three/CityScene.tsx`, `OfficeScene.tsx` | lazy로 불러오는 R3F Canvas와 기존 엔진 연결                                  |
| `src/three/city/`, `src/three/office/`       | 기존 도시·사무실 geometry, 레이아웃, 캐릭터·이동·카메라·충돌 처리            |
| `src/three/sceneData.ts`                     | REST 화면 데이터와 렌더링 데이터 사이의 변환                                 |
| `src/stores/workspaceStore.ts`               | 선택한 패널·시점과 브라우저별 부지·외관 설정                                 |
| `src/api/`, `src/mocks/`                     | 실제 REST 서비스 경계와 브라우저 예시 어댑터                                 |

도시의 건물이나 빈 부지를 누르면 프로젝트 또는 생성 창이 열립니다. 사무실에서는 드래그로 둘러보고 WASD·방향키로 이동하며, Shift로 달리고 Enter로 가까운 대상을 선택합니다. 아래 단축 바의 메뉴는 숫자 키 1~6으로 엽니다. ? 키나 오른쪽 위 도움말 버튼은 화면 안내와 1분 둘러보기를 엽니다. 휠로 확대·축소하고 1·3인칭을 전환할 수 있습니다. 출입구를 통해 실외 산책으로 이동합니다.

문서 등록이나 기준 설계 승인 전에도 사무실에서 걸을 수 있습니다. 시점 버튼을 누르거나 업무 창을 닫으면 이동 입력이 사무실로 돌아옵니다. 업무 창을 연 동안에는 캐릭터 이동 입력을 멈추므로 문서와 검색어를 안전하게 입력할 수 있습니다.

기존 three.js geometry와 화면 형상은 엔진 안에 유지했습니다. R3F Canvas가 제공한 `gl`을 기존 엔진 생성자에 주입하고 `useFrame`의 우선순위 1에서 원래 렌더링을 호출합니다. Canvas 소유의 renderer와 DOM을 엔진이 제거하거나 dispose하지 않으며, 엔진이 만든 geometry·material·texture만 정리합니다.

부지와 외관은 계정·프로젝트 ID별 브라우저 설정입니다. 현재 REST 명세에 부지·건물 저장 계약이 없어 서버 필드나 저장 API를 임의로 추가하지 않았습니다. 다른 브라우저와 배치를 동기화하지 않습니다.

### 계약이 정해질 때 이어갈 부분

문서·설계 항목의 구조화된 `content`는 세부 스키마가 미정이므로 현재는 JSON 객체 경계만 검증합니다. Git·PR 확장은 요구사항의 진입점을 유지하지만 REST 상세 계약이 없어 실제 저장소 연결·PR 게시를 수행하지 않습니다. 추가 자료, 음성 입력 등도 명세에 등록된 계약이 확정되기 전에는 임의 엔드포인트로 호출하지 않습니다.

검사는 기존 `npm run check`와 `npm run test:e2e`를 사용합니다. 3D 좌표·배치·이동 회귀는 `src/three/`의 Vitest로 확인하고, WebGL 화면 진입과 업무 흐름은 Chromium에서 확인합니다. 문서 등록 중 닫힘 방지, 근거 문서 이동, 작업·검증 상태의 분리, 한국시간 기간 입력도 대표 UI 검사에 포함됩니다.

다른 개발 서버가 5173 포트를 사용 중이면 PowerShell에서 `$env:BEEZ_E2E_PORT='5182'`를 지정한 뒤 `npm run test:e2e`를 실행합니다. 기본 포트와 npm 스크립트는 유지합니다.
