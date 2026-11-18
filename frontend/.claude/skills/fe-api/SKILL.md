---
name: fe-api
description: frontend에서 백엔드 API를 연동하거나 바꿀 때 쓰는 절차. 엔드포인트 확인, snake_case Zod 스키마, query/mutation 훅, 에러 응답 처리, 시간 표시, 테스트 목킹을 다룬다.
---

# fe-api

## 백엔드 계약

`dev`의 백엔드 설정(Spring Boot)으로 확인한 내용이다. 백엔드가 바뀌면 이 절을 먼저 고친다.

- 경로는 모두 `/api/**`이다. 개발 서버가 `localhost:8080`으로 프록시한다.
- JSON 키는 snake_case이다. null인 필드는 응답에서 빠진다(`non_null`). 그래서 값이 없을 수 있는 필드는 `.optional()`로 받는다.
- 에러 응답은 `{ "error": { "code": "...", "message": "..." } }`이다. `message`는 빠질 수 있다.
- 시간은 UTC ISO 문자열(예: `2026-10-19T00:00:01.123456Z`)이다.
- 백엔드를 로컬에서 실행하면 OpenAPI는 `http://localhost:8080/v3/api-docs`, Swagger UI는 `/swagger-ui.html`에 있다.
- 연결 확인은 `GET /api/ping`이고, 응답은 `{ ok, service, started_at, server_time }`이다.

## 1. 엔드포인트 확인

- 백엔드가 떠 있으면 `/v3/api-docs`에서 경로, 메서드, 요청 필드, 응답 필드를 확인한다.
- 확인할 수 없으면 응답 형태를 지어내지 않는다. 사용자에게 묻거나, 임시 스키마에 `// TODO: 백엔드 응답 확인`을 달고 그 사실을 보고한다.

## 2. 스키마

- 쓰는 feature 폴더의 `schemas.ts`에 `xxxSchema` 이름으로 둔다.
- 키는 응답 그대로 snake_case로 쓴다([D10](../../../docs/decisions.md)).
- 시간은 `z.iso.datetime()` 문자열로 받고, 화면에 보여줄 때 바꾼다.
- 타입은 `z.infer`로 뽑는다. 직접 쓰지 않는다.

```ts
// features/system/schemas.ts
import { z } from 'zod'

export const pingSchema = z.object({
  ok: z.boolean(),
  service: z.string(),
  started_at: z.iso.datetime(),
  server_time: z.iso.datetime(),
})

export type Ping = z.infer<typeof pingSchema>
```

## 3. 조회와 변경 훅

- 조회는 `useQuery`, 변경은 `useMutation`이다. 변경에 성공하면 관련 key를 `invalidateQueries`로 무효화한다.
- `queryKey`는 `['도메인', ...]` 형식이다.
- axios를 직접 import하지 않고 `@/api/client`의 `api`를 쓴다. 경로는 `/api`를 뺀 나머지만 쓴다.

```ts
// features/system/usePing.ts
import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/client'
import { pingSchema } from './schemas'

export function usePing() {
  return useQuery({
    queryKey: ['system', 'ping'],
    queryFn: () => api.get('/ping').then((res) => pingSchema.parse(res.data)),
  })
}
```

```ts
// features/task/useCreateTask.ts
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { api } from '@/api/client'

const taskSchema = z.object({ id: z.number(), title: z.string() })
type CreateTaskInput = { title: string }

export function useCreateTask() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateTaskInput) =>
      api.post('/tasks', input).then((res) => taskSchema.parse(res.data)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tasks'] }),
  })
}
```

## 4. 에러 처리

- 화면은 `isPending`, `isError`, 데이터가 비었을 때를 모두 처리한다.
- 백엔드 에러 코드가 필요하면 `src/api/error.ts`의 공통 함수로 꺼낸다. 이 파일이 아직 없으면 처음 필요한 기능에서 아래 내용으로 만든다.
- 화면에는 코드별 한국어 메시지를 보여주고, 모르는 코드는 일반 메시지로 처리한다. 백엔드 `message`를 그대로 노출하지 않는다.

```ts
// src/api/error.ts
import { isAxiosError } from 'axios'
import { z } from 'zod'

const apiErrorSchema = z.object({
  error: z.object({ code: z.string(), message: z.string().optional() }),
})

export function getApiErrorCode(err: unknown): string | undefined {
  if (!isAxiosError(err)) return undefined
  const parsed = apiErrorSchema.safeParse(err.response?.data)
  return parsed.success ? parsed.data.error.code : undefined
}
```

## 5. 시간 표시

UTC 문자열은 화면에 보여줄 때만 `Asia/Seoul`로 바꾼다. 날짜 라이브러리는 넣지 않고 `Intl`을 쓴다. 이 파일도 없으면 처음 필요할 때 만든다.

```ts
// src/lib/formatDate.ts
const kst = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
})

export const formatKst = (iso: string) => kst.format(new Date(iso))
```

## 6. 테스트

- **스키마**: 실제 응답 예시로 `parse`가 성공하는지, 필수 필드가 빠지면 실패하는지 확인한다.
- **컴포넌트**: `vi.mock('@/api/client')`로 `api`를 대체한다. `QueryClient`는 테스트마다 새로 만들고 `retry: false`로 둔다. 기본값(3번 재시도)이면 에러 테스트가 느려진다.
- **E2E**: `page.route('**/api/...', (route) => route.fulfill({ json }))`로 응답을 대체한다.

```tsx
// features/system/PingStatus.test.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { PingStatus } from './PingStatus'

vi.mock('@/api/client', () => ({ api: { get: vi.fn() } }))

function renderWithQuery(ui: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

it('서버가 응답하면 서비스 이름을 보여준다', async () => {
  vi.mocked(api.get).mockResolvedValue({
    data: {
      ok: true,
      service: 'beez-backend',
      started_at: '2026-10-19T00:00:00Z',
      server_time: '2026-10-19T00:00:01.123456Z',
    },
  })
  renderWithQuery(<PingStatus />)
  expect(await screen.findByText(/beez-backend/)).toBeInTheDocument()
})

it('응답 형태가 스키마와 다르면 에러를 보여준다', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: { ok: true } })
  renderWithQuery(<PingStatus />)
  expect(await screen.findByRole('alert')).toBeInTheDocument()
})
```

## 7. 마무리

`fe-verify`로 검증한다. 새 엔드포인트를 썼으면 보고할 때 경로와 응답 필드를 함께 적는다.
