import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { useEffect, useRef } from 'react'
import type { RefObject } from 'react'
import { Plus } from 'lucide-react'
import { UNREAD_BADGE } from '@/lib/display'
import { cn } from '@/lib/utils'
import { CityRenderer3D } from './city/CityRenderer3D'
import { nearestStop, RING_PITCH, startRotation, stepStop } from './city/ring'
import type { SceneProject } from './types'

export interface CitySceneProps {
  projects: readonly SceneProject[]
  /** 마지막으로 연 프로젝트의 칸. 도시에 들어오면 이 칸을 가운데에 둔다(D33). */
  visitedLot?: number | null
  /** 빈 부지(새 프로젝트 자리) 칸. 모두 쓰면 null */
  nextLot: number | null
  /** 프로젝트 만들기 창 뒤에 깔 때. 이름표를 숨기고 더 가깝게 본다. */
  backdrop?: boolean
  /** 도시 위에 겹쳐 놓은 제목 영역. 제목 글자·버튼과 겹치는 이름표는 숨긴다. */
  titleRef?: RefObject<HTMLElement | null>
  onProjectSelect: (id: string) => void
  onLotSelect: (lot: number) => void
  /** 건물·이름표에 마우스를 올리거나 이름표에 키보드로 들어오면 그 프로젝트, 벗어나면 null. 터치에서는 부르지 않는다. */
  onProjectHover?: (id: string | null) => void
  /** 호버 카드를 이름표의 설명으로 잇는다(aria-describedby). */
  describedBy?: (id: string) => string | undefined
  className?: string
}
type View = { rot: number; target: number; hover: number | null }

function CityRuntime({
  props,
  overlay,
  root,
}: {
  props: CitySceneProps
  overlay: RefObject<HTMLDivElement | null>
  /** 캔버스와 이름표를 함께 담은 도시 영역. 끌기·휠은 여기서 받는다. */
  root: RefObject<HTMLDivElement | null>
}) {
  const { gl, size } = useThree()
  const engine = useRef<CityRenderer3D | null>(null)
  const latest = useRef(props)
  const start = startRotation(props.visitedLot, props.nextLot)
  const view = useRef<View>({ rot: start, target: start, hover: null })
  useEffect(() => {
    latest.current = props
    engine.current?.setProjects(cityLots(props.projects), props.nextLot)
  }, [props])

  useEffect(() => {
    const renderer = new CityRenderer3D(gl.domElement, true, gl)
    engine.current = renderer
    renderer.setProjects(
      cityLots(latest.current.projects),
      latest.current.nextLot,
    )
    const canvas = gl.domElement
    // 끌기·휠은 캔버스가 아니라 도시 영역 전체에서 받는다. 이름표(HTML 버튼)는 캔버스 위에 겹쳐 있어, 캔버스에만 걸면
    // 이름표 위에서 굴린 휠은 페이지를 스크롤하고 이름표에서 시작한 끌기는 거리를 넘기지 못한다.
    const area: HTMLElement = root.current ?? canvas
    const local = (event: PointerEvent) => {
      const rect = canvas.getBoundingClientRect()
      return [event.clientX - rect.left, event.clientY - rect.top] as const
    }
    const ppu = () =>
      renderer.pixelsPerUnit({ ...view.current, backdrop: false })
    let press: { x: number; rot: number; moved: boolean } | null = null
    // 끈 뒤에는 이름표의 클릭(열기)을 한 번 막는다.
    let dragged = false
    let hovered: string | null = null
    const hover = (id: string | null) => {
      if (id === hovered) return
      hovered = id
      latest.current.onProjectHover?.(id)
    }
    const down = (event: PointerEvent) => {
      if (event.button !== 0) return
      dragged = false
      press = { x: event.clientX, rot: view.current.rot, moved: false }
    }
    // 끌면 고리가 손을 따라 돈다. 앞 칸 근처에서 끈 거리 RING_PITCH만큼이 한 칸이다.
    const move = (event: PointerEvent) => {
      if (press) {
        const dx = event.clientX - press.x
        // 실제로 끌기 시작할 때 포인터를 잡는다. 누르자마자 잡으면 이름표 클릭이 이름표에 가지 않는다.
        if (!press.moved && Math.abs(dx) > 4) {
          press.moved = dragged = true
          area.setPointerCapture(event.pointerId)
          hover(null)
        }
        if (!press.moved) return
        view.current.rot = view.current.target =
          press.rot - dx / ppu() / RING_PITCH
        canvas.style.cursor = 'grabbing'
        return
      }
      // 이름표 위는 버튼이 커서와 클릭을 맡고, 건물은 같이 강조한다.
      const tag = (event.target as Element).closest<HTMLElement>('[data-slot]')
      const slot = tag
        ? Number(tag.dataset.slot)
        : event.target === canvas
          ? renderer.pick(...local(event))
          : undefined
      view.current.hover = slot ?? null
      canvas.style.cursor = slot === undefined ? 'grab' : 'pointer'
      // 터치에는 호버가 없다. 호버 카드 없이 배지만 보인다.
      if (event.pointerType !== 'touch')
        hover(
          latest.current.projects.find((item) => item.lot === slot)?.id ?? null,
        )
    }
    const up = (event: PointerEvent) => {
      if (!press) return
      const moved = press.moved
      press = null
      canvas.style.cursor = 'grab'
      // 놓으면 가장 가까운 멈춤 자리(프로젝트 칸·빈 부지)로 간다. 꾸밈 칸에는 멈추지 않는다.
      if (moved) {
        view.current.target = nearestStop(
          view.current.rot,
          stopsOf(latest.current),
        )
        return
      }
      if (event.target !== canvas) return
      const slot = renderer.pick(...local(event))
      if (slot === undefined) return
      const { projects, nextLot, onProjectSelect, onLotSelect } = latest.current
      const project = projects.find((item) => item.lot === slot)
      if (project) onProjectSelect(project.id)
      else if (slot === nextLot) onLotSelect(slot)
    }
    const leave = () => {
      view.current.hover = null
      hover(null)
    }
    // 휠·트랙패드 스크롤도 고리를 돌린다. 250px마다 다음 멈춤 자리(아래·오른쪽이면 다음 칸)로 가고, 빈 부지 다음은
    // 꾸밈 칸을 한 번에 지나 1번이 온다(사용자 결정 '꾸밈 구간 한 번에 넘기기'). 250px에 못 미친 만큼은 다음 자리 쪽으로
    // 그만큼 돌려 보이고, 멈추면 반을 넘었으면 다음 자리, 아니면 원래 자리로 간다. 도시 위에서만 페이지 스크롤을 막는다.
    let settle = 0
    let base: number | null = null
    let rolled = 0
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      // 고리가 돌면 포인터 아래 건물이 바뀐다. 다음 움직임에서 다시 고른다.
      hover(null)
      const delta =
        Math.abs(event.deltaX) > Math.abs(event.deltaY)
          ? event.deltaX
          : event.deltaY
      const stops = stopsOf(latest.current)
      base ??= nearestStop(view.current.target, stops)
      rolled += event.deltaMode === 1 ? delta * 40 : delta
      while (Math.abs(rolled) >= 250) {
        const dir = rolled > 0 ? 1 : -1
        base = stepStop(base, stops, dir)
        rolled -= dir * 250
      }
      const from = base
      const toward = stepStop(from, stops, rolled < 0 ? -1 : 1)
      view.current.target = from + ((toward - from) * Math.abs(rolled)) / 250
      window.clearTimeout(settle)
      settle = window.setTimeout(() => {
        view.current.target = Math.abs(rolled) >= 125 ? toward : from
        base = null
        rolled = 0
      }, 160)
    }
    const click = (event: MouseEvent) => {
      if (!dragged) return
      dragged = false
      event.stopPropagation()
      event.preventDefault()
    }
    area.addEventListener('pointerdown', down)
    area.addEventListener('pointermove', move)
    area.addEventListener('pointerup', up)
    area.addEventListener('pointerleave', leave)
    area.addEventListener('click', click, true)
    area.addEventListener('wheel', wheel, { passive: false })
    return () => {
      area.removeEventListener('pointerdown', down)
      area.removeEventListener('pointermove', move)
      area.removeEventListener('pointerup', up)
      area.removeEventListener('pointerleave', leave)
      area.removeEventListener('click', click, true)
      area.removeEventListener('wheel', wheel)
      window.clearTimeout(settle)
      renderer.dispose()
      engine.current = null
    }
  }, [gl, root])
  useEffect(() => {
    engine.current?.setSize(size.width, size.height)
  }, [size.width, size.height])

  // A positive priority owns rendering; the city scene renders into R3F's canvas.
  useFrame((_, delta) => {
    const renderer = engine.current
    if (!renderer) return
    const v = view.current
    v.rot += (v.target - v.rot) * Math.min(1, delta * 8)
    renderer.render({ ...v, backdrop: latest.current.backdrop })
    // 이름표가 도시 위에 겹친 제목·설명·만들기 버튼(넓은 화면)과 겹치면 숨긴다. 좁은 화면에서 제목이 도시 바깥 위에 있으면
    // 겹칠 일이 없다.
    const origin = gl.domElement.getBoundingClientRect()
    const blocked = [...(latest.current.titleRef?.current?.children ?? [])].map(
      (element) => {
        const rect = element.getBoundingClientRect()
        return {
          left: rect.left - origin.left - 8,
          right: rect.right - origin.left + 8,
          top: rect.top - origin.top - 8,
          bottom: rect.bottom - origin.top + 8,
        }
      },
    )
    renderer.placeTags(overlay.current, blocked)
  }, 1)
  return null
}

/** 멈춤 자리: 부지가 있는 프로젝트 칸과 빈 부지 칸 */
const stopsOf = ({ projects, nextLot }: CitySceneProps) => [
  ...cityLots(projects).map((lot) => lot.slot),
  ...(nextLot === null ? [] : [nextLot]),
]

const cityLots = (projects: readonly SceneProject[]) =>
  projects.flatMap((project) =>
    project.lot == null
      ? []
      : [{ id: project.id, slot: project.lot, design: project.design ?? '' }],
  )

const tagClass =
  'pointer-events-auto absolute top-0 left-0 inline-flex h-[30px] items-center rounded-full px-3 text-[13px] font-bold whitespace-nowrap'

export default function CityScene(props: CitySceneProps) {
  const overlay = useRef<HTMLDivElement>(null)
  const root = useRef<HTMLDivElement>(null)
  const placed = props.projects.filter((project) => project.lot != null)
  return (
    <div
      ref={root}
      className={cn('relative cursor-grab', props.className ?? 'h-full w-full')}
      role="group"
      aria-label={`돌리는 원형 도시. 프로젝트 ${placed.length}개${props.nextLot === null ? '' : '와 빈 부지 하나'}가 고리를 따라 이어져요`}
    >
      <Canvas
        flat
        dpr={[1, 1.75]}
        gl={{
          antialias: true,
          alpha: false,
          powerPreference: 'high-performance',
        }}
        fallback={
          <p role="alert">
            3D 지도를 표시하지 못했어요. 프로젝트 목록에서 선택해 주세요.
          </p>
        }
      >
        <CityRuntime props={props} overlay={overlay} root={root} />
      </Canvas>
      {/* 캔버스 SCR-04-v2-city의 이름표. 칸 위를 따라다니며 누르면 들어가거나 만든다. */}
      {!props.backdrop && (
        <div
          ref={overlay}
          className="pointer-events-none absolute inset-0 z-[2] overflow-hidden"
        >
          {placed.map((project) => (
            <button
              key={project.id}
              data-slot={project.lot}
              type="button"
              style={{ visibility: 'hidden' }}
              aria-describedby={props.describedBy?.(project.id)}
              className={cn(
                tagClass,
                'max-w-[200px] bg-white text-[#2c291f] shadow-[0_6px_16px_-8px_rgba(44,41,31,0.45),0_0_0_1px_rgba(44,41,31,0.08)]',
              )}
              onClick={() => props.onProjectSelect(project.id)}
              onFocus={(event) => {
                if (event.currentTarget.matches(':focus-visible'))
                  props.onProjectHover?.(project.id)
              }}
              onBlur={() => props.onProjectHover?.(null)}
            >
              <span className="truncate">{project.name}</span>
              {project.unread && (
                <span
                  aria-label={project.unread.label}
                  className={UNREAD_BADGE}
                >
                  {project.unread.text}
                </span>
              )}
              <span
                aria-hidden="true"
                className="absolute -bottom-[5px] left-1/2 -ml-[5px] size-2.5 rotate-45 bg-white shadow-[1px_1px_0_0_rgba(44,41,31,0.08)]"
              />
            </button>
          ))}
          {props.nextLot !== null && (
            <button
              data-slot={props.nextLot}
              type="button"
              style={{ visibility: 'hidden' }}
              className={cn(
                tagClass,
                'gap-1.5 bg-primary text-[#322710] shadow-[0_6px_16px_-8px_rgba(44,41,31,0.45)]',
              )}
              onClick={() => props.onLotSelect(props.nextLot!)}
            >
              <Plus aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
              새 프로젝트
              <span
                aria-hidden="true"
                className="absolute -bottom-[5px] left-1/2 -ml-[5px] size-2.5 rotate-45 bg-primary"
              />
            </button>
          )}
        </div>
      )}
    </div>
  )
}
