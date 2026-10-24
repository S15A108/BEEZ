import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef } from 'react'
import { CityRenderer3D } from './city/CityRenderer3D'
import { citySceneData } from './sceneData'
import type { SceneProject } from './types'

export interface CitySceneProps {
  projects: readonly SceneProject[]
  onProjectSelect: (id: string) => void
  onLotSelect: (lot: number) => void
  className?: string
}

function CityRuntime(props: CitySceneProps) {
  const { gl, size } = useThree()
  const engine = useRef<CityRenderer3D | null>(null)
  const latest = useRef(props)
  const zoom = useRef(1)
  const hover = useRef<number | null>(null)
  const data = useMemo(() => citySceneData(props.projects), [props.projects])
  useEffect(() => {
    latest.current = props
  }, [props])

  useEffect(() => {
    const renderer = new CityRenderer3D(gl.domElement, true, gl)
    engine.current = renderer
    const canvas = gl.domElement
    const hit = (event: PointerEvent) => {
      const rect = canvas.getBoundingClientRect()
      return renderer.screenToWorld(
        event.clientX - rect.left,
        event.clientY - rect.top,
      )?.lot
    }
    const move = (event: PointerEvent) => {
      const lot = hit(event)
      hover.current = lot ?? null
      canvas.style.cursor = lot === undefined ? 'default' : 'pointer'
    }
    const click = (event: PointerEvent) => {
      const lot = hit(event)
      if (lot === undefined) return
      const project = latest.current.projects.find(
        (project) => project.lot === lot,
      )
      if (project) latest.current.onProjectSelect(project.id)
      else latest.current.onLotSelect(lot)
    }
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      zoom.current = Math.max(
        0.8,
        Math.min(1.8, zoom.current * (event.deltaY < 0 ? 1.1 : 1 / 1.1)),
      )
    }
    canvas.addEventListener('pointermove', move)
    canvas.addEventListener('pointerup', click)
    canvas.addEventListener('wheel', wheel, { passive: false })
    return () => {
      canvas.removeEventListener('pointermove', move)
      canvas.removeEventListener('pointerup', click)
      canvas.removeEventListener('wheel', wheel)
      renderer.dispose()
      engine.current = null
    }
  }, [gl])
  useEffect(() => {
    engine.current?.setSize(size.width, size.height)
  }, [size.width, size.height])

  // A positive priority owns rendering; the original procedural scene renders into R3F's canvas.
  useFrame(({ clock }) => {
    engine.current?.render(
      data,
      { x: 0, y: 0, dir: 'up', moving: false },
      clock.elapsedTime,
      {
        zoom: zoom.current,
        focus: { x: 0, y: 0 },
        hover: hover.current,
      },
    )
  }, 1)
  return null
}

export default function CityScene(props: CitySceneProps) {
  return (
    <div
      className={props.className ?? 'h-full w-full'}
      role="img"
      aria-label="3D 프로젝트 도시. 건물을 눌러 입장하거나 빈 부지를 선택하세요. 마우스 휠로 확대할 수 있어요."
    >
      <Canvas
        dpr={[1, 1.75]}
        gl={{
          antialias: true,
          alpha: false,
          stencil: true,
          powerPreference: 'high-performance',
        }}
        fallback={
          <p role="alert">
            3D 지도를 표시하지 못했어요. 프로젝트 목록에서 선택해 주세요.
          </p>
        }
      >
        <CityRuntime {...props} />
      </Canvas>
    </div>
  )
}
