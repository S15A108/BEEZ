import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { useEffect, useRef } from 'react'
import type { RefObject } from 'react'
import { Plus } from 'lucide-react'
import { cn } from '@/lib/utils'
import { CityRenderer3D } from './city/CityRenderer3D'
import { initialRotation, RING_PITCH } from './city/ring'
import type { SceneProject } from './types'

export interface CitySceneProps {
  projects: readonly SceneProject[]
  /** 빈 부지(새 프로젝트 자리) 칸. 모두 쓰면 null */
  nextLot: number | null
  /** 프로젝트 만들기 창 뒤에 깔 때. 이름표를 숨기고 더 가깝게 본다. */
  backdrop?: boolean
  onProjectSelect: (id: string) => void
  onLotSelect: (lot: number) => void
  className?: string
}
type View = { rot: number; target: number; zoom: number; hover: number | null }

function CityRuntime({
  props,
  overlay,
}: {
  props: CitySceneProps
  overlay: RefObject<HTMLDivElement | null>
}) {
  const { gl, size } = useThree()
  const engine = useRef<CityRenderer3D | null>(null)
  const latest = useRef(props)
  const view = useRef<View>({
    rot: initialRotation(props.nextLot),
    target: initialRotation(props.nextLot),
    zoom: 1,
    hover: null,
  })
  useEffect(() => {
    latest.current = props
    engine.current?.setProjects(ringProjects(props.projects), props.nextLot)
  }, [props])

  useEffect(() => {
    const renderer = new CityRenderer3D(gl.domElement, true, gl)
    engine.current = renderer
    renderer.setProjects(
      ringProjects(latest.current.projects),
      latest.current.nextLot,
    )
    const canvas = gl.domElement
    const local = (event: PointerEvent) => {
      const rect = canvas.getBoundingClientRect()
      return [event.clientX - rect.left, event.clientY - rect.top] as const
    }
    const ppu = () =>
      renderer.pixelsPerUnit({ ...view.current, backdrop: false })
    let press: { x: number; rot: number; moved: boolean } | null = null
    const down = (event: PointerEvent) => {
      if (event.button !== 0) return
      press = { x: event.clientX, rot: view.current.rot, moved: false }
      canvas.setPointerCapture(event.pointerId)
    }
    // 끌면 고리가 손을 따라 돈다. 앞 칸 근처에서 끈 거리 RING_PITCH만큼이 한 칸이다.
    const move = (event: PointerEvent) => {
      if (press) {
        const dx = event.clientX - press.x
        if (Math.abs(dx) > 4) press.moved = true
        if (!press.moved) return
        view.current.rot = view.current.target =
          press.rot - dx / ppu() / RING_PITCH
        canvas.style.cursor = 'grabbing'
        return
      }
      const slot = renderer.pick(...local(event))
      view.current.hover = slot ?? null
      canvas.style.cursor = slot === undefined ? 'grab' : 'pointer'
    }
    const up = (event: PointerEvent) => {
      if (!press) return
      const moved = press.moved
      press = null
      canvas.style.cursor = 'grab'
      // 놓으면 가장 가까운 칸이 가운데로 온다.
      if (moved) {
        view.current.target = Math.round(view.current.rot)
        return
      }
      const slot = renderer.pick(...local(event))
      if (slot === undefined) return
      const { projects, nextLot, onProjectSelect, onLotSelect } = latest.current
      const project = projects.find((item) => item.lot === slot)
      if (project) onProjectSelect(project.id)
      else if (slot === nextLot) onLotSelect(slot)
    }
    const leave = () => {
      view.current.hover = null
    }
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      view.current.zoom = Math.max(
        0.8,
        Math.min(1.8, view.current.zoom * (event.deltaY < 0 ? 1.1 : 1 / 1.1)),
      )
    }
    canvas.addEventListener('pointerdown', down)
    canvas.addEventListener('pointermove', move)
    canvas.addEventListener('pointerup', up)
    canvas.addEventListener('pointerleave', leave)
    canvas.addEventListener('wheel', wheel, { passive: false })
    return () => {
      canvas.removeEventListener('pointerdown', down)
      canvas.removeEventListener('pointermove', move)
      canvas.removeEventListener('pointerup', up)
      canvas.removeEventListener('pointerleave', leave)
      canvas.removeEventListener('wheel', wheel)
      renderer.dispose()
      engine.current = null
    }
  }, [gl])
  useEffect(() => {
    engine.current?.setSize(size.width, size.height)
  }, [size.width, size.height])

  // A positive priority owns rendering; the ring scene renders into R3F's canvas.
  useFrame((_, delta) => {
    const renderer = engine.current
    if (!renderer) return
    const v = view.current
    v.rot += (v.target - v.rot) * Math.min(1, delta * 8)
    renderer.render({ ...v, backdrop: latest.current.backdrop })
    // 고리를 돌려 위로 올라간 칸의 이름표가 화면 제목 글자와 겹치지 않게 위 120px 안에서는 숨긴다.
    renderer.placeTags(overlay.current, 120)
  }, 1)
  return null
}

const ringProjects = (projects: readonly SceneProject[]) =>
  projects.flatMap((project) =>
    project.lot == null
      ? []
      : [{ id: project.id, slot: project.lot, design: project.design ?? '' }],
  )

const tagClass =
  'pointer-events-auto absolute top-0 left-0 inline-flex h-[30px] items-center rounded-full px-3 text-[13px] font-bold whitespace-nowrap'

export default function CityScene(props: CitySceneProps) {
  const overlay = useRef<HTMLDivElement>(null)
  const placed = props.projects.filter((project) => project.lot != null)
  return (
    <div
      className={cn('relative cursor-grab', props.className ?? 'h-full w-full')}
      role="group"
      aria-label={`돌리는 원형 도시. 앞 반원에 프로젝트 ${placed.length}개${props.nextLot === null ? '' : '와 빈 부지 하나'}`}
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
        <CityRuntime props={props} overlay={overlay} />
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
              className={cn(
                tagClass,
                'gap-[7px] bg-white text-[#2c291f] shadow-[0_6px_16px_-8px_rgba(44,41,31,0.45),0_0_0_1px_rgba(44,41,31,0.08)]',
              )}
              onClick={() => props.onProjectSelect(project.id)}
            >
              <span
                aria-hidden="true"
                className={cn(
                  'size-2 rounded-full',
                  project.status === 'ready' ? 'bg-[#22a35a]' : 'bg-[#e0b400]',
                )}
              />
              {project.name}
              <span className="font-medium text-[#737373]">
                {project.status === 'ready' ? '진행 중' : '준비 중'}
              </span>
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
