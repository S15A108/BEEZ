import * as THREE from 'three'
import { expect, test, vi } from 'vitest'
import { officeSceneAgents } from '../sceneData'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { buildBee, buildRobot } from './memberFigures'
import { buildAgent } from './agentFigure'
import { avatarAppearance, officeActors } from './office-actors'
import { PERSONAL_SPACE } from './office-life'
import { officeHomeSeats } from './office-work'

// D24: 계정 avatar(회원 설정 캐릭터)로 사무실 모양을 정한다. 옛 bee-*는 회원 설정처럼 가장 가까운 새 캐릭터, 없으면 사람.
test('계정 avatar를 사무실 모양으로 바꾼다', () => {
  expect(avatarAppearance('human')).toBe('human')
  expect(avatarAppearance('robot')).toBe('member-robot')
  expect(avatarAppearance('bee')).toBe('bee')
  expect(avatarAppearance('bee-yellow')).toBe('bee')
  expect(avatarAppearance('bee-blue')).toBe('human')
  expect(avatarAppearance(undefined)).toBe('human')
})

// avatar 없는 팀원의 기대값을 기본 로봇(검은 바이저)에서 사람으로 바꿨다(사용자 승인 2026-11-03, D27).
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

// D27: 에이전트는 팀원과 키가 비슷하고(안테나 끝 1.74 m), 사무실 걷기·앉기 자세가 돌릴 관절을 가진다.
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
