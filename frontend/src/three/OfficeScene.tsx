import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef, useState } from 'react'
import { makeBuilding, ALL_KEYS } from './office/building'
import { officeFloorAt } from './office/dream-floor'
import { firstPersonMove } from './office/first-person'
import { orbitMove } from './office/office-orbit'
import {
  moveContinuous,
  movementInput,
  movementKey,
  movementSpeed,
  smoothRoute,
} from './office/motion'
import type { Point } from './office/motion'
import { OfficeRenderer3D } from './office/OfficeRenderer3D'
import type { OfficeEntity3D, OfficeSceneHit } from './office/OfficeRenderer3D'
import {
  OutdoorStreetRenderer,
  walkableStreetSpawn,
} from './office/OutdoorStreetRenderer'
import { avatarAppearance, officeActors } from './office/office-actors'
import { nearOfficeExit } from './office/office-exit'
import { officeLanding, officeWalkable } from './office/office-navigation'
import { keepsSpace, OfficeLifeSimulation } from './office/office-life'
import { buildOfficeWork, officeHomeSeats } from './office/office-work'
import { findPath } from './office/path'
import { officeSceneAgents, officeSceneSnapshot } from './sceneData'
import type { SceneAgent, SceneProject, SceneTask } from './types'

export interface OfficeSceneProps {
  project: Pick<SceneProject, 'name' | 'id'>
  /** 건물 디자인(D22). 산책 화면의 우리 건물을 도시와 같은 Kenney 모델로 그린다. */
  design?: string
  agents: readonly SceneAgent[]
  tasks: readonly SceneTask[]
  viewMode: 'first' | 'third'
  outdoor: boolean
  onAgentSelect: (id: string) => void
  onTaskSelect: (id: string) => void
  onOutdoorChange?: (outdoor: boolean) => void
  onHintChange?: (hint: string | null) => void
  phase?: 0 | 1 | 2 | 3 | 4
  currentUserId?: string
  /** 내 계정 avatar. 사무실의 '나' 모양을 회원 설정 캐릭터로 그린다(D34). */
  avatar?: string
  interactive?: boolean
  className?: string
}

type Player = OfficeEntity3D & { heading: number }
interface Drag {
  id: number
  x: number
  y: number
  startX: number
  startY: number
  moved: boolean
}

function OfficeRuntime(
  props: OfficeSceneProps & { onOutdoorChange: (outdoor: boolean) => void },
) {
  const { gl, size } = useThree()
  const phase = props.phase ?? 4
  const agents = useMemo(() => officeSceneAgents(props.agents), [props.agents])
  // 인원 객체를 넘기면 팀원 수와 상관없는 고정 사무실(D36)이라 에이전트에 기대지 않는다. 30초마다 바뀌는 에이전트
  // 상태로 바닥을 새로 만들면 아래 lastFloor 분기가 내 클릭 걷기·엘리베이터 걷기를 멈춘다(PR #128 리뷰).
  const floor = useMemo(
    () =>
      officeFloorAt(
        makeBuilding(ALL_KEYS, 'glass', undefined, {}).floors[1],
        phase,
      ),
    [phase],
  )
  const snapshot = useMemo(
    () => officeSceneSnapshot(props.project, agents, props.tasks),
    [props.project, agents, props.tasks],
  )
  const work = useMemo(
    () => buildOfficeWork(floor, snapshot),
    [floor, snapshot],
  )
  const actors = useMemo(
    () =>
      officeActors(
        floor,
        agents,
        phase,
        props.currentUserId ??
          agents.find((agent) => agent.kind === 'human')?.id,
        officeHomeSeats(floor, agents),
      ),
    [floor, agents, phase, props.currentUserId],
  )
  const latest = useRef({ props, floor, actors, work, phase })
  const engine = useRef<OfficeRenderer3D | null>(null)
  const street = useRef<OutdoorStreetRenderer | null>(null)
  const life = useRef<OfficeLifeSimulation | null>(null)
  const outdoorPlayer = useRef<Point | null>(null)
  const player = useRef<Player>({
    id: 'beez-player',
    x: 7.5 * 32,
    y: 4.5 * 32,
    heading: -Math.PI / 2,
    dir: 'left',
    moving: false,
    floor: 1,
    color: '#d6bf95',
    appearance: 'human',
  })
  const route = useRef<Point[]>([])
  const pendingExit = useRef(false)
  const keys = useRef(new Set<string>())
  const zoom = useRef(1)
  const focus = useRef({ x: 15 * 32, y: 9 * 32 })
  const lastFloor = useRef<typeof floor | null>(null)
  const clearInput = useRef(() => {})
  const hint = useRef<string | null>(null)
  useEffect(() => {
    latest.current = { props, floor, actors, work, phase }
  }, [props, floor, actors, work, phase])

  useEffect(() => {
    const canvas = gl.domElement
    const renderer = new OfficeRenderer3D(
      canvas,
      true,
      latest.current.phase === 4,
      gl,
    )
    const simulation = new OfficeLifeSimulation(latest.current.props.project.id)
    engine.current = renderer
    life.current = simulation
    canvas.setAttribute('tabindex', '0')
    canvas.setAttribute(
      'aria-label',
      '3D 사무실. 드래그로 둘러보고 WASD나 방향키로 이동하세요. Shift로 달리고 Enter로 대상을 선택해요.',
    )
    let drag: Drag | null = null
    const clear = () => {
      keys.current.clear()
      route.current = []
      drag = null
    }
    clearInput.current = clear
    const free = (x: number, y: number) =>
      officeWalkable(latest.current.floor, x, y)
    const walkTo = (destination: Point) => {
      const currentFloor = latest.current.floor
      const blocked = currentFloor.blocked.map((row, y) =>
        row.map((value, x) => value || currentFloor.grid[y][x] === '#'),
      )
      const points = findPath(
        blocked,
        Math.floor(player.current.x / 32),
        Math.floor((player.current.y - 4) / 32),
        Math.floor(destination.x / 32),
        Math.floor(destination.y / 32),
      ).map(([x, y]) => ({ x: x * 32 + 16, y: y * 32 + 27 }))
      points.push(destination)
      route.current = smoothRoute(player.current, points, free)
    }
    const exit = () => {
      const portal = latest.current.floor.outdoorExit
      if (!portal || latest.current.phase < 4) return
      if (nearOfficeExit(player.current, portal)) {
        clear()
        latest.current.props.onOutdoorChange(true)
        return
      }
      walkTo(portal.landing)
      pendingExit.current = !!route.current.length
    }
    const activate = (hit: OfficeSceneHit) => {
      if (hit.outdoorExit) {
        exit()
        return true
      }
      if (hit.entityId && hit.entityId !== 'beez-player') {
        latest.current.props.onAgentSelect(hit.entityId)
        return true
      }
      if (hit.workHit?.target.type === 'task') {
        latest.current.props.onTaskSelect(hit.workHit.target.id)
        return true
      }
      return false
    }
    const down = (event: PointerEvent) => {
      if (
        event.button !== 0 ||
        drag ||
        latest.current.props.interactive === false
      )
        return
      canvas.focus({ preventScroll: true })
      drag = {
        id: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        startX: event.clientX,
        startY: event.clientY,
        moved: false,
      }
      canvas.setPointerCapture(event.pointerId)
    }
    const move = (event: PointerEvent) => {
      if (!drag || drag.id !== event.pointerId) return
      if (
        Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > 5
      )
        drag.moved = true
      if (drag.moved) {
        if (latest.current.props.outdoor)
          street.current?.look.drag(
            event.clientX - drag.x,
            event.clientY - drag.y,
          )
        else renderer.rotateView(event.clientX - drag.x, event.clientY - drag.y)
      }
      drag.x = event.clientX
      drag.y = event.clientY
    }
    const up = (event: PointerEvent) => {
      if (!drag || drag.id !== event.pointerId) return
      const clicked = !drag.moved
      drag = null
      if (canvas.hasPointerCapture(event.pointerId))
        canvas.releasePointerCapture(event.pointerId)
      if (!clicked) return
      const rect = canvas.getBoundingClientRect(),
        x = event.clientX - rect.left,
        y = event.clientY - rect.top
      if (latest.current.props.outdoor) {
        if (
          outdoorPlayer.current &&
          street.current?.aimedDoor(outdoorPlayer.current, x, y)
        )
          latest.current.props.onOutdoorChange(false)
        return
      }
      const hit = renderer.screenToWorld(x, y)
      if (
        !hit ||
        activate(hit) ||
        latest.current.props.viewMode === 'first' ||
        latest.current.phase < 3
      )
        return
      const destination = {
        x: Math.floor(hit.x / 32) * 32 + 16,
        y: Math.floor(hit.y / 32) * 32 + 27,
      }
      if (free(destination.x, destination.y)) {
        pendingExit.current = false
        walkTo(destination)
      }
    }
    const cancel = (event: PointerEvent) => {
      if (drag?.id === event.pointerId) drag = null
    }
    const keyDown = (event: KeyboardEvent) => {
      if (latest.current.props.interactive === false) return
      const key = movementKey(event.key)
      if (
        [
          'w',
          'a',
          's',
          'd',
          'ArrowUp',
          'ArrowDown',
          'ArrowLeft',
          'ArrowRight',
          'Shift',
        ].includes(key)
      ) {
        event.preventDefault()
        keys.current.add(key)
        route.current = []
        pendingExit.current = false
      }
      if (event.key !== 'Enter' || event.repeat) return
      event.preventDefault()
      if (latest.current.props.outdoor) {
        if (
          outdoorPlayer.current &&
          street.current?.nearDoor(outdoorPlayer.current)
        )
          latest.current.props.onOutdoorChange(false)
      } else if (
        // 시작 전에도 엘리베이터 앞에서 시작하므로, 산책 출구가 닫힌 동안에는 Enter를 비비·가리킨 대상에 넘긴다.
        latest.current.phase >= 4 &&
        nearOfficeExit(player.current, latest.current.floor.outdoorExit)
      )
        exit()
      else if (latest.current.props.viewMode === 'first') {
        if (renderer.aimedTarget) activate(renderer.aimedTarget)
        // 가리킨 대상이 없으면 단축 바의 '비비 Enter'대로 비비를 연다.
        else latest.current.props.onAgentSelect('bibi')
      } else {
        const nearest = latest.current.actors
          .map((actor) => ({
            actor,
            distance: Math.hypot(
              actor.x - player.current.x,
              actor.y - player.current.y,
            ),
          }))
          .sort((a, b) => a.distance - b.distance)[0]
        latest.current.props.onAgentSelect(
          nearest && nearest.distance < 100 ? nearest.actor.id : 'bibi',
        )
      }
    }
    const keyUp = (event: KeyboardEvent) =>
      keys.current.delete(movementKey(event.key))
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      zoom.current = Math.max(
        0.7,
        Math.min(2, zoom.current * (event.deltaY < 0 ? 1.1 : 1 / 1.1)),
      )
    }
    canvas.addEventListener('pointerdown', down)
    canvas.addEventListener('pointermove', move)
    canvas.addEventListener('pointerup', up)
    canvas.addEventListener('pointercancel', cancel)
    canvas.addEventListener('keydown', keyDown)
    canvas.addEventListener('keyup', keyUp)
    canvas.addEventListener('blur', clear)
    canvas.addEventListener('wheel', wheel, { passive: false })
    window.addEventListener('blur', clear)
    document.addEventListener('visibilitychange', clear)
    return () => {
      clear()
      canvas.removeEventListener('pointerdown', down)
      canvas.removeEventListener('pointermove', move)
      canvas.removeEventListener('pointerup', up)
      canvas.removeEventListener('pointercancel', cancel)
      canvas.removeEventListener('keydown', keyDown)
      canvas.removeEventListener('keyup', keyUp)
      canvas.removeEventListener('blur', clear)
      canvas.removeEventListener('wheel', wheel)
      window.removeEventListener('blur', clear)
      document.removeEventListener('visibilitychange', clear)
      clearInput.current = () => {}
      street.current?.dispose()
      street.current = null
      outdoorPlayer.current = null
      simulation.dispose()
      renderer.dispose()
      life.current = null
      engine.current = null
    }
  }, [gl, props.project.id])

  useEffect(() => {
    clearInput.current()
    if (props.interactive === false) return
    const frame = requestAnimationFrame(() =>
      gl.domElement.focus({ preventScroll: true }),
    )
    return () => cancelAnimationFrame(frame)
  }, [gl, props.interactive, props.viewMode, props.outdoor])

  useEffect(() => {
    engine.current?.setSize(size.width, size.height)
    street.current?.setSize(size.width, size.height)
  }, [size.width, size.height, props.project.id])

  useFrame(({ clock }, seconds) => {
    const renderer = engine.current,
      simulation = life.current
    if (!renderer || !simulation) return
    const state = latest.current,
      dt = Math.min(0.06, seconds),
      time = clock.elapsedTime * 1000,
      bounds = state.floor.footprint
    if (state.props.outdoor && bounds) {
      if (!street.current) {
        street.current = new OutdoorStreetRenderer(
          gl.domElement,
          bounds,
          state.props.project.name,
          gl,
          state.props.design,
        )
        street.current.setSize(size.width, size.height)
        outdoorPlayer.current = walkableStreetSpawn(
          bounds,
          street.current.canWalk,
        )
        keys.current.clear()
      }
      const me = outdoorPlayer.current
      if (!me) return
      const direction = firstPersonMove(
          movementInput(keys.current),
          street.current.look.yaw,
        ),
        speed = movementSpeed(135, keys.current) * dt
      Object.assign(
        me,
        moveContinuous(
          me,
          { x: direction.x * speed, y: direction.y * speed },
          street.current.canWalk,
        ),
      )
      street.current.render(me, time, dt)
      // 사무실의 data-player-x처럼 산책 위치를 남겨 E2E가 걸음을 확인한다.
      gl.domElement.setAttribute('data-street-x', me.x.toFixed(2))
      gl.domElement.setAttribute('data-street-y', me.y.toFixed(2))
      return
    }
    if (street.current) {
      street.current.dispose()
      street.current = null
      outdoorPlayer.current = null
      keys.current.clear()
      if (state.floor.outdoorExit)
        Object.assign(player.current, state.floor.outdoorExit.landing, {
          moving: false,
        })
    }
    const free = (x: number, y: number) => officeWalkable(state.floor, x, y)
    if (lastFloor.current !== state.floor) {
      lastFloor.current = state.floor
      route.current = []
      pendingExit.current = false
      if (state.phase >= 3) {
        const landing = officeLanding(state.floor, player.current)
        if (landing) Object.assign(player.current, landing)
      }
    }
    const residents = simulation.step(state.floor, state.actors, dt, {
      enabled: state.phase >= 3,
      caller: player.current,
    })
    const me = player.current,
      before = { x: me.x, y: me.y }
    // 나도 다른 사람을 뚫고 지나가지 않는다(다가가는 걸음만 막고 비켜 가는 걸음은 둔다).
    const step = (move: { x: number; y: number }) => {
      const next = moveContinuous(me, move, free)
      if (keepsSpace(me, next, residents)) Object.assign(me, next)
    }
    if (state.phase >= 3 && state.props.interactive !== false) {
      const input = movementInput(keys.current),
        direction =
          state.props.viewMode === 'first'
            ? firstPersonMove(
                input,
                renderer.isFirstPerson ? renderer.viewHeading : me.heading,
              )
            : orbitMove(input, renderer.orbitYaw),
        speed = movementSpeed(105, keys.current) * dt
      if (direction.x || direction.y)
        step({ x: direction.x * speed, y: direction.y * speed })
      else if (route.current.length) {
        const next = route.current[0],
          distance = Math.hypot(next.x - me.x, next.y - me.y)
        if (distance < 3) route.current.shift()
        else
          step({
            x: ((next.x - me.x) / distance) * Math.min(speed, distance),
            y: ((next.y - me.y) / distance) * Math.min(speed, distance),
          })
      }
    }
    const dx = me.x - before.x,
      dy = me.y - before.y
    me.moving = Math.hypot(dx, dy) > 0.01
    if (pendingExit.current && nearOfficeExit(me, state.floor.outdoorExit)) {
      pendingExit.current = false
      route.current = []
      state.props.onOutdoorChange(true)
    }
    if (me.moving)
      me.dir =
        Math.abs(dx) > Math.abs(dy)
          ? dx > 0
            ? 'right'
            : 'left'
          : dy > 0
            ? 'down'
            : 'up'
    if (state.props.viewMode === 'first' && renderer.isFirstPerson)
      me.heading = renderer.viewHeading
    else if (me.moving) me.heading = Math.atan2(dx, dy)
    const target =
      state.phase >= 3
        ? {
            x: (state.floor.W / 2) * 32,
            y: (bounds ? (bounds.back + bounds.front) / 2 : 9) * 32,
          }
        : [
            { x: 160, y: 128 },
            { x: 160, y: 128 },
            { x: 224, y: 256 },
            { x: 352, y: 256 },
          ][state.phase]
    const ease = 1 - Math.exp(-dt * 2)
    focus.current.x += (target.x - focus.current.x) * ease
    focus.current.y += (target.y - focus.current.y) * ease
    me.appearance = avatarAppearance(state.props.avatar)
    renderer.render(state.floor, [...residents, me], time, {
      zoom: [2.6, 2.1, 1.6, 1.16, 1.16][state.phase] * zoom.current,
      focus: focus.current,
      firstPersonId: state.props.viewMode === 'first' ? me.id : undefined,
      work: state.work,
    })
    const nextHint =
      state.props.viewMode === 'first'
        ? (renderer.aimedTarget?.label ?? null)
        : null
    if (nextHint !== hint.current) {
      hint.current = nextHint
      state.props.onHintChange?.(nextHint)
    }
    gl.domElement.setAttribute('data-player-x', me.x.toFixed(2))
    gl.domElement.setAttribute('data-player-y', me.y.toFixed(2))
  }, 1)
  return null
}

export default function OfficeScene(props: OfficeSceneProps) {
  const [localOutdoor, setLocalOutdoor] = useState(props.outdoor)
  const outdoor = props.onOutdoorChange ? props.outdoor : localOutdoor
  return (
    <div
      className={props.className ?? 'h-full w-full'}
      role="img"
      aria-label={outdoor ? '3D 도시 거리 산책' : '3D 팀 사무실'}
    >
      <Canvas
        dpr={[1, 1.75]}
        gl={{
          antialias: true,
          alpha: true,
          stencil: true,
          powerPreference: 'high-performance',
        }}
        fallback={
          <p role="alert">
            3D 공간을 표시하지 못했어요. 프로젝트 메뉴에서 업무를 이어 갈 수
            있어요.
          </p>
        }
      >
        <OfficeRuntime
          {...props}
          outdoor={outdoor}
          onOutdoorChange={props.onOutdoorChange ?? setLocalOutdoor}
        />
      </Canvas>
    </div>
  )
}
