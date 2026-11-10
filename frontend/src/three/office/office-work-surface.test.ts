import { assert, test } from 'vitest'
import { OfficeWorkSurface } from './office-work-surface'
import { buildOfficeWork } from './office-work'
import { makeBuilding } from './building'
import { RECEPTIONIST } from './social'
import type { Snapshot, Task } from '../types'

test('display textures upload only on content changes, hit real tasks and release their GPU resources', () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document')
  let paints = 0
  const ctx = {
    clearRect: () => {
      paints++
    },
    beginPath() {},
    roundRect() {},
    fill() {},
    fillText() {},
    measureText: (text: string) => ({ width: [...text].length * 20 }),
  }
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { createElement: () => ({ getContext: () => ctx }) },
  })
  try {
    const floor = makeBuilding(['lobby', 'dev']).floors[1]
    const task: Task = {
      id: 'T-1',
      title: '로그인 화면',
      description: '',
      scope: 'FE',
      assignee_id: 'FE',
      status: 'todo',
      decision_keys: [],
      decision_refs: [],
      needs_recheck: false,
      started_at: null,
      updated_at: '2030-05-15T12:00:00Z',
      req_refs: [],
    }
    const work = buildOfficeWork(floor, {
      project: { name: '실제 프로젝트' } as Snapshot['project'],
      agents: [
        { ...RECEPTIONIST, id: 'FE', name: '프런트엔드', home_zone: 'dev' },
      ],
      tasks: [task],
      events: [],
    })
    const board = new OfficeWorkSurface('board', 5, 2)
    board.update(work)
    const texture = board.mesh.material.map!,
      version = texture.version,
      count = paints
    board.update(work)
    board.update(structuredClone(work))
    board.update({ ...work, monitors: {} })
    assert.strictEqual(paints, count)
    assert.strictEqual(
      texture.version,
      version,
      'unchanged snapshots never upload another texture',
    )
    assert.deepStrictEqual(board.hit(0.12, 0.71).target, {
      type: 'task',
      id: 'T-1',
    })
    assert.match(board.hit(0.12, 0.71).title, /로그인 화면/)
    const moved = buildOfficeWork(floor, {
      project: { name: '실제 프로젝트' } as Snapshot['project'],
      agents: [],
      tasks: [{ ...task, status: 'passed' }],
      events: [],
    })
    board.update(moved)
    assert.isOk(texture.version > version)
    assert.strictEqual(board.hit(0.12, 0.71).target.type, 'zone')
    assert.deepStrictEqual(board.hit(0.85, 0.71).target, {
      type: 'task',
      id: 'T-1',
    })
    let released = 0
    texture.addEventListener('dispose', () => released++)
    board.mesh.material.addEventListener('dispose', () => released++)
    board.mesh.geometry.addEventListener('dispose', () => released++)
    board.dispose()
    assert.strictEqual(released, 3)
    const monitor = new OfficeWorkSurface('monitor', 1, 0.7),
      content = Object.values(work.monitors)[0]
    monitor.update(work, content)
    assert.match(monitor.hit().title, /프런트엔드/)
    assert.deepStrictEqual(monitor.hit().target, { type: 'task', id: 'T-1' })
    monitor.dispose()
    const projection = new OfficeWorkSurface('projection', 5.6, 2.18)
    projection.update(work)
    assert.match(projection.hit(0.5, 0.95).title, /프로젝터/)
    assert.deepStrictEqual(projection.hit(0.12, 0.71).target, {
      type: 'task',
      id: 'T-1',
    })
    projection.update(moved)
    assert.deepStrictEqual(projection.hit(0.85, 0.71).target, {
      type: 'task',
      id: 'T-1',
    })
    projection.dispose()
  } finally {
    if (previous) Object.defineProperty(globalThis, 'document', previous)
    else Reflect.deleteProperty(globalThis, 'document')
  }
})

test('사무실 화면은 실제 상태가 바뀔 때만 다시 그리고, 누르면 그 창을 연다', () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document')
  let paints = 0
  const noop = () => {}
  const ctx = {
    clearRect: () => {
      paints++
    },
    beginPath: noop,
    roundRect: noop,
    fill: noop,
    fillText: noop,
    fillRect: noop,
    moveTo: noop,
    lineTo: noop,
    stroke: noop,
    save: noop,
    restore: noop,
    translate: noop,
    rotate: noop,
    measureText: (text: string) => ({ width: [...text].length * 20 }),
  }
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { createElement: () => ({ getContext: () => ctx }) },
  })
  try {
    const wall = new OfficeWorkSurface('notes', 3.6, 1.6)
    const screens = {
      wall: [
        {
          label: '제안',
          count: 4,
          notes: [
            { id: '41', key: 'CP-4', title: '로그인 정책', status: 'PROPOSED' },
          ],
        },
        { label: '검토 중', count: 0, notes: [] },
        { label: '승인', count: 0, notes: [] },
        { label: '발행 완료', count: 0, notes: [] },
        { label: '반려·거둠', count: 0, notes: [] },
      ],
      design: null,
      progress: [],
    }
    wall.update(undefined, undefined, screens)
    const count = paints
    wall.update(undefined, undefined, structuredClone(screens))
    assert.strictEqual(paints, count, '같은 상태면 다시 그리지 않는다')
    // 첫 칸 첫 메모(왼쪽 위)를 누르면 그 제안을 연다.
    assert.deepStrictEqual(wall.hit(0.08, 0.7).target, {
      type: 'pane',
      id: 'changes',
      focus: '41',
    })
    assert.match(wall.hit(0.08, 0.7).title, /CP-4/)
    assert.deepStrictEqual(wall.hit(0.9, 0.1).target, {
      type: 'pane',
      id: 'changes',
    })
    wall.dispose()
    const tv = new OfficeWorkSurface('graph', 1.6, 0.9)
    tv.update(undefined, undefined, screens)
    assert.deepStrictEqual(tv.hit().target, { type: 'pane', id: 'relations' })
    assert.match(tv.hit().detail, /아직 기준 설계가 없어요/)
    tv.dispose()
  } finally {
    if (previous) Object.defineProperty(globalThis, 'document', previous)
    else Reflect.deleteProperty(globalThis, 'document')
  }
})
