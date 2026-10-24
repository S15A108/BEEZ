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
