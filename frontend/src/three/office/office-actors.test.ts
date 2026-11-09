import * as THREE from 'three'
import { expect, test, vi } from 'vitest'
import { officeSceneAgents } from '../sceneData'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { buildBee, buildRobot } from './memberFigures'
import { buildAgent } from './agentFigure'
import {
  avatarAppearance,
  bibiSpot,
  officeActors,
  type BibiActivity,
} from './office-actors'
import {
  isOfficeWorkingStatus,
  OfficeLifeSimulation,
  PERSONAL_SPACE,
  type OfficeLifeActor,
} from './office-life'
import { officeWalkable } from './office-navigation'
import { officeHomeSeats } from './office-work'
import { BRICK_PLAN } from './plans/brick'
import { GLASS_PLAN } from './plans/glass'
import { MODERN_PLAN } from './plans/modern'
import { TOWER_PLAN } from './plans/tower'
import { WOOD_PLAN } from './plans/wood'

// D34: 계정 avatar(회원 설정 캐릭터)로 사무실 모양을 정한다. 옛 bee-*는 회원 설정처럼 가장 가까운 새 캐릭터, 없으면 사람.
test('계정 avatar를 사무실 모양으로 바꾼다', () => {
  expect(avatarAppearance('human')).toBe('human')
  expect(avatarAppearance('robot')).toBe('member-robot')
  expect(avatarAppearance('bee')).toBe('bee')
  expect(avatarAppearance('bee-yellow')).toBe('bee')
  expect(avatarAppearance('bee-blue')).toBe('human')
  expect(avatarAppearance(undefined)).toBe('human')
})

// avatar 없는 팀원의 기대값을 기본 로봇(검은 바이저)에서 사람으로 바꿨다(사용자 승인 2026-11-03, D35).
test('팀원은 avatar 모양(없으면 사람)으로, 팀원의 AI 에이전트는 에이전트 모양으로 그린다', () => {
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', null, { dev: 2, lead: 1, humandesk: 1 })
      .floors[1],
    4,
  )
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human', avatar: 'robot' },
    { id: 'member:22', name: '김동료', kind: 'agent', avatar: 'bee' },
    { id: 'member:23', name: '박팀원', kind: 'agent' },
    {
      id: 'agent:22',
      name: '김동료의 에이전트',
      kind: 'agent',
      owner: 'member:22',
      status: 'offline',
    },
  ])
  const actors = officeActors(
    floor,
    agents,
    4,
    'member:21',
    officeHomeSeats(floor, agents),
  )
  const appearance = (id: string) =>
    actors.find((actor) => actor.id === id)?.appearance
  expect(appearance('member:21')).toBeUndefined()
  expect(appearance('member:22')).toBe('bee')
  expect(appearance('member:23')).toBe('human')
  expect(appearance('agent:22')).toBe('agent')
  // 연결이 끊긴 에이전트는 돌아다니지 않는다.
  expect(actors.find((actor) => actor.id === 'agent:22')?.stationary).toBe(true)
})

// D35: 에이전트는 팀원과 키가 비슷하고(안테나 끝 1.74 m), 사무실 걷기·앉기 자세가 돌릴 관절을 가진다.
test('에이전트는 팀원 키의 관절 있는 로봇이고 주인 색 띠를 두른다', () => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  const agent = buildAgent('#4f86c6', 'online')
  const size = new THREE.Box3()
    .setFromObject(agent)
    .getSize(new THREE.Vector3())
  expect(size.y).toBeCloseTo(1.74, 1)
  for (const name of [
    'arm-left',
    'arm-right',
    'elbow-left',
    'elbow-right',
    'leg-left',
    'leg-right',
    'knee-left',
    'knee-right',
  ])
    expect(agent.getObjectByName(name)?.children.length).toBeGreaterThan(0)
  // 엉덩이 축은 루트 바로 아래라 앉는 높이를 셀 수 있다.
  expect(agent.getObjectByName('leg-left')?.parent).toBe(agent)
  const owner = new THREE.Color('#4f86c6')
  const colors: THREE.Color[] = []
  agent.traverse((object) => {
    const material = (object as THREE.Mesh).material as
      THREE.MeshStandardMaterial | undefined
    if (material?.color) colors.push(material.color)
  })
  expect(colors.some((color) => color.equals(owner))).toBe(true)
  vi.restoreAllMocks()
})

// 로봇·벌은 걷고 앉게 팔·다리를 축 그룹으로 묶었다. 서 있는 모양(크기)은 보드 모델과 같고, 같은 키로 맞추면
// 개인 거리 안에 들어간다.
test('회원 캐릭터 로봇·벌은 보드 크기 그대로 팔·다리 축을 가진다', () => {
  for (const [figure, height, width] of [
    [buildRobot(), 1.215, 0.72],
    [buildBee(), 1.195, 0.657],
  ] as const) {
    const size = new THREE.Box3()
      .setFromObject(figure)
      .getSize(new THREE.Vector3())
    expect(size.y).toBeCloseTo(height, 2)
    expect(size.x).toBeCloseTo(width, 2)
    for (const name of ['arm-left', 'arm-right', 'leg-left', 'leg-right'])
      expect(figure.getObjectByName(name)?.children.length).toBeGreaterThan(0)
    // 사무실 키 1.5타일로 맞췄을 때의 폭(px)
    expect((width / height) * 1.5 * 32).toBeLessThan(PERSONAL_SPACE)
  }
})

// D37: 고정 사무실에서 팀원은 자기 자리 옆에 서 있고(위치 API 없음, 가구는 쓰지 않음), 에이전트는 작업 현황 상태마다 정한 자리로 간다.
test('팀원은 자기 자리 옆에 서 있고 에이전트는 상태별 자리로 간다', () => {
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', null, { dev: 8, lead: 1, humandesk: 1 })
      .floors[1],
    4,
  )
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human' },
    { id: 'member:22', name: '김동료', kind: 'agent' },
    { id: 'member:26', name: '박팀원', kind: 'agent' },
    {
      id: 'agent:26',
      name: '박팀원의 에이전트',
      kind: 'agent',
      owner: '26',
      status: 'working',
    },
    {
      id: 'agent:22',
      name: '김동료의 에이전트',
      kind: 'agent',
      owner: '22',
      status: 'waiting',
      note: '승인 대기',
    },
    {
      id: 'agent:25',
      name: '최팀원의 에이전트',
      kind: 'agent',
      owner: '25',
      status: 'offline',
    },
    {
      id: 'agent:23',
      name: '정팀원의 에이전트',
      kind: 'agent',
      owner: '23',
      status: 'validating',
    },
    {
      id: 'agent:21',
      name: '나의 에이전트',
      kind: 'agent',
      owner: '21',
      status: 'online',
    },
  ])
  const homes = officeHomeSeats(floor, agents)
  const actors = officeActors(floor, agents, 4, 'member:21', homes)
  const at = (id: string) => actors.find((actor) => actor.id === id)!
  const spots = (list: { x: number; y: number }[] | undefined) =>
    (list ?? []).map((seat) => ({ x: seat.x * 32 + 16, y: seat.y * 32 + 27 }))
  const place = (id: string) => ({ x: at(id).x, y: at(id).y })
  // 팀원은 가구를 쓰지 않는다: 의자에 앉지 않고 자기 의자 옆(2칸 안, 의자와 다른 곳)에 서 있다.
  // 기대값을 seated에서 standing으로 바꿨다(사용자 승인 2026-11-03).
  const chair22 = spots([homes.get('member:22')!])[0]
  expect(at('member:22')).toMatchObject({ stationary: true, standing: true })
  expect(at('member:22').seated).toBeFalsy()
  expect(place('member:22')).not.toEqual(chair22)
  expect(
    Math.hypot(chair22.x - at('member:22').x, chair22.y - at('member:22').y),
  ).toBeLessThan(2 * 32)
  expect(at('member:21')).toBeUndefined()
  // 작업 중: 주인의 빈 아일랜드 의자에 앉고(사용자 결정), 옆에 선 주인과는 사람 사이 거리보다 멀다
  const owner = spots([homes.get('member:26')!])[0]
  expect(spots(floor.seats.dev!.slice(0, 12))).toContainEqual(place('agent:26'))
  expect(place('agent:26')).toEqual(owner)
  expect(
    Math.hypot(
      at('member:26').x - at('agent:26').x,
      at('member:26').y - at('agent:26').y,
    ),
  ).toBeGreaterThan(PERSONAL_SPACE)
  expect(at('agent:26')).toMatchObject({
    status: 'working',
    agentStatus: 'working',
  })
  // 대기: 허들 스툴, 말풍선에 대기 이유
  expect(spots(floor.seats.meeting)).toContainEqual(place('agent:22'))
  expect(at('agent:22')).toMatchObject({
    agentStatus: 'idle',
    note: '승인 대기',
  })
  // 검증 중: 칸반 워룸 화면 앞
  expect(spots(floor.seats.board)).toContainEqual(place('agent:23'))
  // 연결 전: 라운지 충전 자리에서 움직이지 않는다
  expect(spots(floor.seats.lobby)).toContainEqual(place('agent:25'))
  expect(at('agent:25')).toMatchObject({
    stationary: true,
    agentStatus: 'offline',
  })
  expect(at('agent:25').tag).toMatch(/ · 연결 전$/)
  // 연결됨(할 일 없음): 주인 자리 뒤에 서 있다
  const mine = spots([homes.get('member:21')!])[0]
  expect(at('agent:21')).toMatchObject({
    standing: true,
    agentStatus: 'online',
  })
  expect(
    Math.hypot(mine.x - at('agent:21').x, mine.y - at('agent:21').y),
  ).toBeLessThan(32)
  // 에이전트는 산책하지 않는다: 연결 전 말고도 모두 자리에 묶인 상태로 걷는다
  for (const id of ['agent:26', 'agent:22', 'agent:23', 'agent:21'])
    expect(isOfficeWorkingStatus(at(id).status)).toBe(true)
})

// 사용자 결정 '라운지 가운데에 세움'(2026-11-03): 고정 사무실에서는 프로젝트를 시작한 뒤(lead 에이전트가 있어도) 비비가
// lead 자리에 서 있다. 상태(BEE-A04)를 읽기 전에는 걷지 않는다(D35).
test('고정 사무실에서는 시작한 뒤에도 비비가 라운지 가운데 lead 자리에 서 있다', () => {
  const floor = makeDreamFloor(
    makeBuilding(ALL_KEYS, 'glass', null, { dev: 8, lead: 1, humandesk: 1 })
      .floors[1],
    4,
  )
  expect(floor.zoneSigns).toBeTruthy()
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human' },
  ])
  const lead = floor.seats.lead![0]
  const bibi = officeActors(floor, agents, 4, 'member:21', new Map()).find(
    (actor) => actor.appearance === 'bibi',
  )
  expect(bibi).toMatchObject({
    id: 'bibi',
    x: lead.x * 32 + 16,
    y: lead.y * 32 + 27,
    moving: false,
    stationary: true,
  })
})

// D35: 비비는 현재 상태(BEE-A04)대로 공용 문서함·회의실·라운지 가운데에 서고, 건물 배치마다 두 구역의 자리가 있다.
const PLANS = {
  brick: BRICK_PLAN,
  modern: MODERN_PLAN,
  tower: TOWER_PLAN,
  wood: WOOD_PLAN,
  glass: GLASS_PLAN,
}
const DESIGNS = Object.keys(PLANS) as (keyof typeof PLANS)[]
const bibiFloor = (design: string) =>
  makeDreamFloor(makeBuilding(ALL_KEYS, design, null, {}).floors[1], 4)
const feet = (spot: { x: number; y: number }) => ({
  x: spot.x * 32 + 16,
  y: spot.y * 32 + 27,
})

test.each(DESIGNS)('%s: 상태마다 비비 자리가 정해진다', (design) => {
  const floor = bibiFloor(design)
  const lounge = floor.seats.lead![0]
  expect(bibiSpot(floor, 'DOCUMENT_REVIEW')).toBe(floor.bibiSpots!.documents)
  expect(bibiSpot(floor, 'MEETING')).toBe(floor.bibiSpots!.meeting)
  // 한가함, 상태 없음(아직 못 읽음), 없는 API(404)는 모두 라운지 가운데다.
  expect(bibiSpot(floor, 'IDLE')).toBe(lounge)
  expect(bibiSpot(floor, undefined)).toBe(lounge)
  expect(bibiSpot(floor, null)).toBe(lounge)
})

test('자리를 모르는 옛 바닥에서는 상태와 상관없이 lead 자리다', () => {
  const floor = bibiFloor('glass')
  const old = { ...floor, bibiSpots: undefined }
  expect(bibiSpot(old, 'MEETING')).toBe(floor.seats.lead![0])
})

test.each(DESIGNS)(
  '%s: 두 구역 자리는 그 구역 안의 빈 칸이고 의자에서 사람 사이 거리만큼 떨어져 있다',
  (design) => {
    const floor = bibiFloor(design)
    const plan = PLANS[design]
    const zone = (name: string) =>
      plan.zones.find((item) => item.name === name)!
    for (const [kind, name] of [
      ['documents', '공용 문서함'],
      ['meeting', '회의실'],
    ] as const) {
      // 구역 경계와 자리는 같은 캔버스 배치(m)라 좁히기(D42) 전 값끼리 비교한다.
      const at = plan.bibiSpots[kind]
      const bounds = zone(name)
      expect(at.x).toBeGreaterThan(bounds.x0)
      expect(at.x).toBeLessThan(bounds.x1)
      expect(at.z).toBeGreaterThan(bounds.z0)
      expect(at.z).toBeLessThan(bounds.z1)
      const { x, y } = feet(floor.bibiSpots![kind])
      expect(officeWalkable(floor, x, y)).toBe(true)
      for (const seat of Object.values(floor.seats).flat()) {
        const home = feet(seat!)
        expect(Math.hypot(home.x - x, home.y - y)).toBeGreaterThanOrEqual(
          PERSONAL_SPACE,
        )
      }
    }
  },
)

// 비비가 있는 사무실을 돌리고 프레임마다 비비를 돌려준다. others는 그동안 그 자리에 있는 사람이다.
function bibiLife(
  floor: ReturnType<typeof bibiFloor>,
  id: string,
  agents: ReturnType<typeof officeSceneAgents>,
  homes: ReturnType<typeof officeHomeSeats> = new Map(),
) {
  const simulation = new OfficeLifeSimulation(id)
  const live = (
    activity: BibiActivity | null | undefined,
    seconds: number,
    others: OfficeLifeActor[] = [],
  ) => {
    const seen: { x: number; y: number; moving: boolean }[] = []
    for (let t = 0; t < seconds; t += 0.05) {
      const actors = officeActors(
        floor,
        agents,
        4,
        'member:21',
        homes,
        activity,
      )
      seen.push(
        simulation
          .step(floor, [...actors, ...others], 0.05)
          .find(
            (actor) => 'appearance' in actor && actor.appearance === 'bibi',
          )!,
      )
    }
    return seen
  }
  return { live, simulation }
}
const expectAt = (
  spot: { x: number; y: number },
  list: { x: number; y: number }[],
) => {
  const home = feet(spot)
  for (const point of list) {
    expect(point.x).toBeCloseTo(home.x, 0)
    expect(point.y).toBeCloseTo(home.y, 0)
  }
}

// 앉은 사람: 대기·검증 중 에이전트가 회의실·워룸 자리에 앞에서부터 앉는다(D37). 모두는 회의실·문서함·워룸 자리가 다 차고 충전
// 자리에 내 에이전트(연결 끊김은 보통 하나뿐이다)가 앉은 경우다. 좁은 곳에 끼면 비비가 나오지 못한다(유리 회의실 테이블 머리,
// 벽돌 칸반 옆 자리에서 그랬다).
test.each(
  DESIGNS.flatMap((design) =>
    ([0, 1, 2, 3, '모두'] as const).map((seated) => [design, seated] as const),
  ),
)(
  '%s, 앉은 사람 %s: 비비는 상태가 바뀔 때만 걸어가 그 자리에 머문다',
  (design, seated) => {
    const floor = bibiFloor(design)
    const agents = officeSceneAgents([
      { id: 'bibi', name: '비비', kind: 'lead' },
      { id: 'member:21', name: '나', kind: 'human' },
    ])
    const sitting = (
      seated === '모두'
        ? [
            ...floor.seats.meeting!,
            ...floor.seats.humandesk!,
            ...floor.seats.board!,
            floor.seats.lobby![0],
          ]
        : [
            ...floor.seats.meeting!.slice(0, seated),
            ...floor.seats.board!.slice(0, seated),
          ]
    ).map((seat, index) => ({
      id: `sitting-${index}`,
      ...feet(seat),
      dir: seat.facing,
      moving: false,
      stationary: true,
    }))
    const { live, simulation } = bibiLife(
      floor,
      `bibi-${design}-${seated}`,
      agents,
    )
    const { documents, meeting } = floor.bibiSpots!
    const lounge = floor.seats.lead![0]
    // 아직 못 읽은 동안 라운지에 있고, 처음 읽은 자리에는 걷지 않고 바로 선다(새로 고칠 때마다 걸어가지 않게).
    expectAt(lounge, live(undefined, 0.5, sitting))
    expectAt(meeting, live('MEETING', 2, sitting))
    // 같은 상태를 다시 읽어도 움직이지 않는다.
    expect(live('MEETING', 20, sitting).some((item) => item.moving)).toBe(false)
    // 바뀌면 걷는다: 순간 이동하지 않고 움직이다가 새 자리에 머문다.
    const walk = live('DOCUMENT_REVIEW', 40, sitting)
    expect(walk[1].moving).toBe(true)
    expect(
      Math.hypot(walk[1].x - feet(meeting).x, walk[1].y - feet(meeting).y),
    ).toBeLessThan(10)
    expectAt(documents, walk.slice(-20))
    expect(walk.at(-1)!.moving).toBe(false)
    // 한가해지거나 API가 없어지면(404) 라운지 가운데로 돌아간다.
    expectAt(lounge, live('IDLE', 40, sitting).slice(-20))
    expectAt(meeting, live('MEETING', 40, sitting).slice(-20))
    expectAt(lounge, live(null, 40, sitting).slice(-20))
    expect(live(null, 20, sitting).some((item) => item.moving)).toBe(false)
    simulation.dispose()
  },
)

// 서 있는 팀원은 움직이지 않으므로(D37) 그 자리가 비비의 길을 막으면 비비가 영영 닿지 못한다.
test.each(DESIGNS)(
  '%s: 팀원이 모두 자리 옆에 서 있어도 비비는 세 자리를 오간다',
  (design) => {
    const floor = bibiFloor(design)
    const agents = officeSceneAgents([
      { id: 'bibi', name: '비비', kind: 'lead' },
      { id: 'member:21', name: '나', kind: 'human' },
      ...Array.from({ length: 12 }, (_, index) => ({
        id: `member:${30 + index}`,
        name: `팀원 ${index}`,
        kind: 'agent' as const,
      })),
    ])
    const { live, simulation } = bibiLife(
      floor,
      `bibi-team-${design}`,
      agents,
      officeHomeSeats(floor, agents),
    )
    const { documents, meeting } = floor.bibiSpots!
    expectAt(meeting, live('MEETING', 2))
    expectAt(documents, live('DOCUMENT_REVIEW', 60).slice(-20))
    expectAt(floor.seats.lead![0], live('IDLE', 60).slice(-20))
    expectAt(meeting, live('MEETING', 60).slice(-20))
    simulation.dispose()
  },
)

// 유리 회의실에는 문이 하나뿐이라 우회로가 없다. 막힌 채 제자리걸음을 하지 않고 서서 기다리다가, 비키면 다시 걸어간다.
test('서 있는 팀원이 하나뿐인 길을 막으면 멈춰 기다렸다가 비키면 다시 걸어간다', () => {
  const floor = bibiFloor('glass')
  const agents = officeSceneAgents([
    { id: 'bibi', name: '비비', kind: 'lead' },
    { id: 'member:21', name: '나', kind: 'human' },
  ])
  const { live, simulation } = bibiLife(floor, 'bibi-door', agents)
  const door = {
    id: 'standing-in-door',
    x: 12 * 32 + 16,
    y: 6 * 32 + 27,
    dir: 'down' as const,
    moving: false,
    stationary: true,
  }
  live('MEETING', 2, [door])
  live('DOCUMENT_REVIEW', 10, [door])
  const blocked = live('DOCUMENT_REVIEW', 12, [door])
  const { documents } = floor.bibiSpots!
  expect(
    Math.hypot(
      blocked.at(-1)!.x - feet(documents).x,
      blocked.at(-1)!.y - feet(documents).y,
    ),
  ).toBeGreaterThan(100)
  expect(
    blocked.filter((item) => item.moving).length / blocked.length,
  ).toBeLessThan(0.5)
  expectAt(documents, live('DOCUMENT_REVIEW', 40).slice(-20))
  simulation.dispose()
})
