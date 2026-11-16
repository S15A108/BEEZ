import type { ReactNode } from 'react'
import { FileCheck, MessageCircle, Network } from 'lucide-react'
// 나의 도시와 같은 원형 도시 그림(D33). 3D를 띄우지 않고 나의 도시 화면을 찍은 정적 그림이라 첫 화면이 가볍다.
import cityPreview from '@/assets/city-preview.webp'

// 로그인·회원가입·비밀번호 찾기가 같이 쓰는 2단 틀: 왼쪽 소개, 오른쪽 폼 카드. 화면마다 카드 안의 폼만 바뀐다.
// 좁은 폭(로그인·가입은 메일 링크로 휴대폰에서도 열린다)에서는 폼을 먼저 보이고 소개 그림은 뺀다.
export function AuthLayout({
  titleId,
  children,
}: {
  titleId: string
  children: ReactNode
}) {
  return (
    <main className="mx-auto grid min-h-[calc(100dvh-4rem)] max-w-6xl items-center gap-14 px-6 py-12 lg:grid-cols-[1.15fr_0.85fr] lg:px-10">
      <section
        aria-labelledby={titleId}
        className="rounded-[22px] border bg-card p-7 shadow-sm sm:p-10 lg:order-2"
      >
        {children}
      </section>
      <section className="space-y-6 lg:order-1">
        <h2 className="text-4xl leading-tight font-bold">
          우리 팀 프로젝트가
          <br />
          하나의 도시가 돼요
        </h2>
        <p className="max-w-md text-base leading-7 text-muted-foreground">
          프로젝트마다 건물이 하나씩 생겨요. 사람과 AI 팀장 비비가 승인된 설계를
          기준으로 함께 일해요.
        </p>
        <figure className="hidden overflow-hidden rounded-[22px] border bg-muted lg:block">
          <img
            src={cityPreview}
            alt="BEEZ 나의 도시 화면 미리 보기"
            className="block h-70 w-full object-cover"
          />
        </figure>
        <ul className="flex flex-wrap gap-2">
          {(
            [
              [FileCheck, '공용 문서와 승인 기준'],
              [MessageCircle, '근거를 기억하는 비비'],
              [Network, '작업과 동료를 잇는 맥락'],
            ] as const
          ).map(([Icon, label]) => (
            <li
              key={label}
              className="inline-flex items-center gap-2 rounded-full border bg-card px-3 py-1.5 text-sm"
            >
              <Icon className="size-4" />
              {label}
            </li>
          ))}
        </ul>
      </section>
    </main>
  )
}
