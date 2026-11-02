import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Box, CheckCheck, LayoutGrid } from 'lucide-react'
import { BibiFace } from '@/components/BibiFace'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

export type TourMode = 'welcome' | 'map' | number | null

type Target = 'office' | 'card' | 'hotbar' | 'todo' | 'top' | 'bibi'

// 공용 가이드 줄(row-scr06-tour-v2): 환영 → 둘러보기 1~6 → ? 키 화면 안내. 문구는 보드 그대로.
const steps: { target: Target; title: string; body: string }[] = [
  {
    target: 'office',
    title: '3D 사무실',
    body: '팀원, 비비, 에이전트가 함께 일하는 곳이에요. 이름표를 누르면 그 화면이 열려요. 바닥을 클릭하거나 WASD로 움직이고, 왼쪽 아래에서 1인칭·산책으로 바꿀 수 있어요.',
  },
  {
    target: 'card',
    title: '프로젝트 카드',
    body: '지금 들어와 있는 프로젝트와 내 역할이에요. 처리할 일은 큰 숫자로 알려 주고, 아래 목록에서 팀원과 에이전트가 어디서 무엇을 하는지 볼 수 있어요. 왼쪽 화살표를 누르면 나의 도시로 돌아가요.',
  },
  {
    target: 'hotbar',
    title: '단축 바',
    body: '일할 때 여는 메뉴예요. 공용 문서함부터 대시보드까지 6개가 있고 숫자 키 1~6으로도 열 수 있어요. 가운데 둥근 버튼은 비비예요. 처리할 일이 생기면 칸에 빨간 숫자가 붙어요. 팀 관리와 에이전트 연결은 설정에 있어요.',
  },
  {
    target: 'todo',
    title: '지금 할 일',
    body: '나에게 온 일만 모아 보여 줘요. 처음에는 이 자리에 역할별 시작 가이드가 떠요. 다 끝내면 검토할 변경, 읽지 않은 알림, 시작할 작업이 여기에 쌓여요.',
  },
  {
    target: 'top',
    title: '알림 · 설정',
    body: '벨에는 새 알림이 숫자로 붙고, 누르면 바로 아래에 목록이 열려요. 톱니에서는 팀 관리와 내 에이전트 연결을 열어요.',
  },
  {
    target: 'bibi',
    title: '비비',
    body: 'AI 팀장 비비예요. 설계 의도나 변경 영향이 궁금하면 물어보세요. 공용 문서와 승인 설계에서 근거를 찾아 출처와 함께 답해요.',
  },
]

// ? 키 화면 안내의 한 줄 설명(보드 SCR-06-v2-tour-map). 시점·산책은 1번 사무실에 딸린 칸이다.
const mapNotes: {
  target: Target | 'view'
  number: number
  title: string
  body: string
}[] = [
  {
    target: 'office',
    number: 1,
    title: '3D 사무실',
    body: '비비, 팀원, 에이전트가 있는 곳이에요. 이름표를 누르면 그 화면이 열려요. 바닥 클릭이나 WASD로 움직여요.',
  },
  {
    target: 'view',
    number: 1,
    title: '시점 · 산책',
    body: '1인칭, 3인칭을 바꾸거나 밖으로 산책해요.',
  },
  {
    target: 'card',
    number: 2,
    title: '프로젝트 카드',
    body: '지금 프로젝트와 내 역할, 작업·문서 숫자, 팀을 보여 줘요. 숫자를 누르면 그 메뉴로 가요. 왼쪽 화살표는 나의 도시로 가요.',
  },
  {
    target: 'hotbar',
    number: 3,
    title: '단축 바',
    body: '일할 메뉴 6개. 숫자 키 1~6으로 열어요.',
  },
  {
    target: 'todo',
    number: 4,
    title: '지금 할 일',
    body: '나에게 온 일만 모아요. 처음에는 시작 가이드가 이 자리에 떠요.',
  },
  {
    target: 'top',
    number: 5,
    title: '알림 · 설정',
    body: '벨은 알림, 톱니는 설정이에요.',
  },
  {
    target: 'bibi',
    number: 6,
    title: '비비',
    body: '설계가 궁금하면 물어보세요. 승인 설계에서 근거를 찾아 답해요.',
  },
]

const radius: Record<Target | 'view', number> = {
  office: 28,
  card: 22,
  hotbar: 999,
  todo: 22,
  top: 16,
  bibi: 999,
  view: 16,
}

// 강조할 영역. 사무실은 화면 전체라 보드처럼 가운데 60%만 밝힌다. 나머지는 요소 둘레를 조금 넓힌다.
function areaOf(target: Target | 'view') {
  const width = window.innerWidth
  const height = window.innerHeight
  if (target === 'office')
    return {
      left: width * 0.2,
      top: height * 0.2,
      width: width * 0.6,
      height: height * 0.6,
    }
  const rect = document
    .querySelector(`[data-tour="${target}"]`)
    ?.getBoundingClientRect()
  if (!rect) return undefined
  // 비비는 노란 테두리 바깥으로 틀이 보이게 조금 더 띄운다.
  const pad = target === 'bibi' ? 8 : 6
  return {
    left: rect.left - pad,
    top: rect.top - pad,
    width: rect.width + pad * 2,
    height: rect.height + pad * 2,
  }
}
type Area = NonNullable<ReturnType<typeof areaOf>>

// 말풍선 자리와 꼬리(보드 단계별 배치). 영역을 가리지 않게 바깥쪽에 둔다.
function bubbleOf(target: Target, area: Area | undefined) {
  const width = window.innerWidth
  const height = window.innerHeight
  const center = {
    box: {
      left: '50%',
      top: '50%',
      transform: 'translate(-50%,-50%)',
    } as CSSProperties,
    arrow: undefined as CSSProperties | undefined,
  }
  if (!area) return center
  switch (target) {
    case 'office':
      return {
        box: { right: 24, top: height / 3 },
        arrow: { left: -7, top: 44 },
      }
    case 'card':
      return {
        box: { left: area.left + area.width + 20, top: area.top + 8 },
        arrow: { left: -7, top: 36 },
      }
    case 'todo':
      return {
        box: { right: width - area.left + 20, top: area.top + 60 },
        arrow: { right: -7, top: 40 },
      }
    case 'top':
      return {
        box: { right: 16, top: area.top + area.height + 20 },
        arrow: { right: 46, top: -7 },
      }
    case 'bibi':
      // 비비: 단축 바 가운데 둥근 버튼(D안) 바로 위. 말풍선과 꼬리를 버튼 가운데에 맞춘다.
      return {
        box: {
          left: area.left + area.width / 2,
          bottom: height - area.top + 12,
          transform: 'translateX(-50%)',
        },
        arrow: { left: '50%', bottom: -7, marginLeft: -7 },
      }
    default:
      // 단축 바: 가운데 위.
      return {
        box: {
          left: '50%',
          bottom: height - area.top + 24,
          transform: 'translateX(-50%)',
        },
        arrow: { left: '50%', bottom: -7, marginLeft: -7 },
      }
  }
}

// 공용 가이드: 환영 → 둘러보기 1~6 → ? 키 화면 안내. 팀장·팀원 모두 같다.
// API 없음: 봤는지를 계정 기준으로 서버에 저장하는 필드가 없어 이 브라우저에만 남긴다(백엔드 메모 1).
export function TourGuide({
  accountId,
  mode,
  onChange,
}: {
  accountId?: string
  mode: TourMode
  onChange: (mode: TourMode) => void
}) {
  const [, setSize] = useState(0)
  useEffect(() => {
    const resize = () => setSize((value) => value + 1)
    const key = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      const typing =
        !!target &&
        (target.isContentEditable ||
          ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
      if (event.key === '?' && !typing) {
        event.preventDefault()
        onChange(mode === 'map' ? null : 'map')
      }
      if (event.key === 'Escape' && mode !== null) onChange(null)
    }
    window.addEventListener('resize', resize)
    window.addEventListener('keydown', key)
    return () => {
      window.removeEventListener('resize', resize)
      window.removeEventListener('keydown', key)
    }
  }, [mode, onChange])
  const finish = () => {
    try {
      if (accountId) localStorage.setItem(`beez.tour.${accountId}`, 'seen')
    } catch {
      /* 저장소가 없어도 가이드는 그대로 닫는다. */
    }
    onChange(null)
  }
  if (mode === null) return null
  // 환영 창은 Radix Dialog로 연다. 포커스를 창 안에 가두고 Esc로 닫는다(PR #72 전수 리뷰 UI-1).
  // 뒤 화면 숫자 단축키는 가이드가 열린 동안 단축 바가 끈다(WorkspaceHud가 disabled로 넘김).
  if (mode === 'welcome')
    return (
      <Dialog open onOpenChange={(open) => !open && onChange(null)}>
        <DialogContent
          showCloseButton={false}
          className="block max-w-[580px] gap-0 rounded-3xl bg-card px-10 pt-9 pb-7 text-center shadow-2xl ring-0 sm:max-w-[580px]"
        >
          <BibiFace className="mx-auto size-18" />
          <DialogTitle className="mt-3 text-2xl leading-8 font-bold">
            BEEZ 사무실에 오신 걸 환영해요
          </DialogTitle>
          <DialogDescription className="mt-2 text-sm leading-[22px] text-stone-600">
            팀이 같은 승인 설계를 기준으로 함께 일하는 곳이에요.
            <br />
            처음이라면 화면을 1분만 둘러보세요.
          </DialogDescription>
          <div className="mt-4 grid gap-2 rounded-[14px] border border-[#f1d98a] bg-[#fff6d6] px-3.5 py-3 text-left">
            <strong className="text-[13px] leading-[18px] font-bold">
              문서 초안 4종이 승인되면 프로젝트가 시작돼요
            </strong>
            <span className="flex flex-wrap gap-1.5">
              {['기획안', 'ERD', '요구사항 명세서', 'API 명세서'].map(
                (kind) => (
                  <span
                    key={kind}
                    className="rounded-full bg-white px-2.5 py-0.5 text-xs leading-[18px] font-semibold text-stone-600"
                  >
                    {kind}
                  </span>
                ),
              )}
            </span>
          </div>
          <ul className="mt-6 grid grid-cols-3 gap-2.5">
            {(
              [
                [Box, '3D 사무실', '팀원, 비비, 에이전트가 함께 있어요'],
                [LayoutGrid, '단축 바', '일할 메뉴를 숫자 키로 열어요'],
                [CheckCheck, '지금 할 일', '나에게 온 일만 모아 보여 줘요'],
              ] as const
            ).map(([Icon, title, text]) => (
              <li
                key={title}
                className="grid justify-items-center gap-1.5 rounded-2xl bg-[#f7f5ef] px-3 py-4"
              >
                <span className="grid size-10 place-items-center rounded-xl bg-white text-[#8a6a00]">
                  <Icon className="size-5.5" />
                </span>
                <strong className="text-[13px] leading-[18px] font-bold">
                  {title}
                </strong>
                <span className="text-xs leading-[17px] text-muted-foreground">
                  {text}
                </span>
              </li>
            ))}
          </ul>
          <div className="mt-6 grid gap-2.5">
            <Button
              autoFocus
              className="h-11 rounded-xl text-[15px] font-bold"
              onClick={() => onChange(0)}
            >
              1분 둘러보기
            </Button>
            <Button
              variant="ghost"
              className="h-10 rounded-xl text-muted-foreground"
              onClick={finish}
            >
              건너뛰고 바로 시작하기
            </Button>
          </div>
          <p className="mt-3 text-xs leading-[18px] text-muted-foreground/70">
            둘러보기는 <Kbd>?</Kbd> 키로 언제든 다시 볼 수 있어요.
          </p>
        </DialogContent>
      </Dialog>
    )
  if (mode === 'map')
    return (
      <div className="fixed inset-0 z-50 bg-[rgba(28,25,18,0.38)]">
        <section
          role="dialog"
          aria-modal="true"
          aria-labelledby="tour-map-title"
          className="absolute top-[18px] left-1/2 flex -translate-x-1/2 items-center gap-3 rounded-full bg-card py-2 pr-2.5 pl-4 shadow-lg"
        >
          <strong id="tour-map-title" className="text-sm">
            화면 안내
          </strong>
          <span className="text-xs text-muted-foreground">
            <Kbd>?</Kbd> 또는 <Kbd>Esc</Kbd>로 닫기
          </span>
          <Button
            size="sm"
            autoFocus
            className="h-[30px] rounded-full px-3 text-xs font-bold"
            onClick={() => onChange('welcome')}
          >
            처음부터 둘러보기
          </Button>
        </section>
        {mapNotes.map((note) => {
          const area = areaOf(note.target)
          if (!area) return null
          return (
            <div key={`${note.target}-${note.number}`}>
              {/* 사무실은 화면 전체라 점선 틀 없이 설명만 둔다(보드). */}
              {note.target !== 'office' && (
                <div
                  aria-hidden="true"
                  className="absolute box-border border-2 border-dashed border-primary"
                  style={{ ...area, borderRadius: radius[note.target] }}
                />
              )}
              <MapNote note={note} area={area} />
            </div>
          )
        })}
      </div>
    )
  const step = steps[mode]
  const area = areaOf(step.target)
  const bubble = bubbleOf(step.target, area)
  const last = mode === steps.length - 1
  return (
    <div className="fixed inset-0 z-50">
      {area ? (
        <div
          aria-hidden="true"
          className="pointer-events-none absolute"
          style={{
            ...area,
            borderRadius: radius[step.target],
            boxShadow:
              '0 0 0 3px var(--primary), 0 0 0 9999px rgba(28, 25, 18, 0.62)',
          }}
        />
      ) : (
        <div
          aria-hidden="true"
          className="absolute inset-0 bg-[rgba(28,25,18,0.62)]"
        />
      )}
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="tour-step-title"
        className="absolute box-border w-[380px] max-w-[calc(100vw-32px)] rounded-[18px] bg-card px-5 py-[18px] shadow-2xl"
        style={bubble.box}
      >
        {bubble.arrow && (
          <span
            aria-hidden="true"
            className="absolute size-3.5 rotate-45 bg-card"
            style={bubble.arrow}
          />
        )}
        <p className="text-xs leading-4 font-bold text-[#8a6a00]">
          화면 둘러보기 · {mode + 1} / {steps.length}
        </p>
        <h2
          id="tour-step-title"
          className="mt-1.5 text-[17px] leading-6 font-bold"
        >
          {step.title}
        </h2>
        <p className="mt-1.5 text-[13px] leading-5 text-stone-600">
          {step.body}
        </p>
        <div className="mt-4 flex items-center justify-between">
          <span className="flex gap-[5px]" aria-hidden="true">
            {steps.map((item, index) => (
              <span
                key={item.target}
                className={cn(
                  'h-1.5 rounded-full',
                  index === mode ? 'w-[18px] bg-primary' : 'w-1.5 bg-[#e5e2d9]',
                )}
              />
            ))}
          </span>
          <span className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              className="h-[34px] rounded-[10px] px-3"
              onClick={() => onChange(mode > 0 ? mode - 1 : 'welcome')}
            >
              이전
            </Button>
            <Button
              size="sm"
              autoFocus
              className="h-[34px] rounded-[10px] px-3.5 font-bold"
              onClick={() => (last ? finish() : onChange(mode + 1))}
            >
              {last ? '시작하기' : '다음'}
            </Button>
          </span>
        </div>
        <button
          type="button"
          className="absolute top-4 right-[18px] text-xs text-muted-foreground/70 hover:text-foreground"
          onClick={finish}
        >
          건너뛰기
        </button>
      </section>
    </div>
  )
}

function Kbd({ children }: { children: string }) {
  return (
    <kbd className="rounded-md border px-[5px] font-sans text-[inherit]">
      {children}
    </kbd>
  )
}

// 화면 안내의 어두운 설명 칩. 영역 바깥쪽(보드 배치)에 둔다.
function MapNote({
  note,
  area,
}: {
  note: (typeof mapNotes)[number]
  area: Area
}) {
  const width = window.innerWidth
  const height = window.innerHeight
  const right = note.target === 'todo' || note.target === 'top'
  const place: CSSProperties =
    note.target === 'office'
      ? { left: area.left + 16, top: area.top + area.height - 80 }
      : note.target === 'view'
        ? { left: area.left + 6, bottom: height - area.top + 22 }
        : note.target === 'card'
          ? { left: area.left + area.width + 14, top: area.top + 86 }
          : note.target === 'hotbar'
            ? { left: area.left + 3, bottom: height - area.top + 24 }
            : note.target === 'todo'
              ? { right: width - area.left + 18, top: area.top + 30 }
              : note.target === 'top'
                ? { right: width - area.left + 10, top: area.top + 4 }
                : { left: area.left + area.width + 14, bottom: 30 }
  return (
    <div
      className={cn(
        'absolute box-border flex max-w-[300px] gap-2 rounded-[14px] bg-[#3a362b] px-3 py-2.5 text-white shadow-lg',
        right && 'flex-row-reverse text-right',
      )}
      style={place}
    >
      <span className="grid size-[22px] shrink-0 place-items-center rounded-full bg-primary text-xs font-extrabold text-primary-foreground">
        {note.number}
      </span>
      <span className="grid gap-0.5">
        <strong className="text-[13px] leading-[18px]">{note.title}</strong>
        <span className="text-xs leading-[17px] text-[#e7e3d6]">
          {note.body}
        </span>
      </span>
    </div>
  )
}
