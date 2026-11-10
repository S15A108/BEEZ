import { describe, expect, it } from 'vitest'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { officeFurnitureKey, type WorkMonitor } from './office-work'
import {
  codingAppOf,
  codingLines,
  codingScreen,
  designBoard,
  progressRows,
  proposalWall,
} from './office-screens'

const proposal = (id: string, status: string) => ({
  id,
  key: `CP-${id}`,
  title: `제안 ${id}`,
  status,
})

describe('변경 제안 메모 벽', () => {
  it('제안 상태를 변경 제안 창의 상태 이름 칸으로 나누고 칸마다 세 장까지 붙인다', () => {
    const wall = proposalWall([
      proposal('1', 'PROPOSED'),
      proposal('2', 'IN_REVIEW'),
      proposal('3', 'IN_REVIEW'),
      proposal('4', 'IN_REVIEW'),
      proposal('5', 'IN_REVIEW'),
      proposal('6', 'APPROVED'),
      proposal('7', 'PUBLISHED'),
      proposal('8', 'REJECTED'),
      proposal('9', 'WITHDRAWN'),
    ])
    expect(wall.map((column) => column.label)).toEqual([
      '제안',
      '검토 중',
      '승인',
      '발행 완료',
      '반려·거둠',
    ])
    expect(wall.map((column) => column.count)).toEqual([1, 4, 1, 1, 2])
    expect(wall[1].notes.map((note) => note.key)).toEqual([
      'CP-2',
      'CP-3',
      'CP-4',
    ])
    expect(wall[4].notes).toEqual([
      { id: '8', key: 'CP-8', title: '제안 8', status: 'REJECTED' },
      { id: '9', key: 'CP-9', title: '제안 9', status: 'WITHDRAWN' },
    ])
  })

  it('목록 첫 쪽보다 많으면 상태별 집계로 센다', () => {
    const wall = proposalWall(
      [proposal('1', 'PUBLISHED')],
      [
        { status: 'PUBLISHED', count: 12 },
        { status: 'REJECTED', count: 2 },
        { status: 'WITHDRAWN', count: 1 },
      ],
    )
    expect(wall[3]).toMatchObject({ count: 12, notes: [{ key: 'CP-1' }] })
    expect(wall[4].count).toBe(3)
    expect(wall[0].count).toBe(0)
  })
})

describe('설계 관계 TV', () => {
  it('기준 설계 항목을 종류별로 세고 진행 중 제안이 바꾸는 항목을 겹치지 않게 센다', () => {
    const board = designBoard(
      3,
      [
        { design_item_id: '11', kind: 'REQUIREMENT' },
        { design_item_id: '12', kind: 'REQUIREMENT' },
        { design_item_id: '21', kind: 'API_CONTRACT' },
        { design_item_id: '31', kind: 'POLICY' },
      ],
      false,
      [
        {
          items: [
            { operation: 'UPDATE', design_item_id: '11' },
            { operation: 'RETIRE', design_item_id: '21' },
          ],
        },
        {
          items: [
            { operation: 'UPDATE', design_item_id: '11' },
            { operation: 'CREATE', kind: 'DATA_CONTRACT' },
          ],
        },
      ],
    )
    expect(board).toMatchObject({
      version: 3,
      total: 4,
      more: false,
      proposals: 2,
    })
    expect(
      board.kinds.map(({ label, count, touched }) => [label, count, touched]),
    ).toEqual([
      ['요구사항', 2, 1],
      ['API 계약', 1, 1],
      ['데이터 계약', 0, 1],
      ['정책', 1, 0],
      ['제약조건', 0, 0],
    ])
  })
})

describe('프로젝트 진행 화면', () => {
  it('받은 집계만 줄로 만들고 못 받은 줄은 지어내지 않는다', () => {
    expect(progressRows()).toEqual([])
    expect(
      progressRows(
        {
          by_status: [
            { status: 'PUBLISHED', count: 3 },
            { status: 'IN_REVIEW', count: 1 },
          ],
          review_required_count: 1,
        },
        {
          by_kind: [
            { kind: 'PLAN', approved_count: 1 },
            { kind: 'ERD', approved_count: 0 },
            { kind: 'REQUIREMENTS', approved_count: 2 },
            { kind: 'API_SPEC', approved_count: 0 },
          ],
        },
      ),
    ).toEqual([
      { label: '기준 문서 승인', done: 2, total: 4, tone: 'done' },
      { label: '변경 제안 발행', done: 3, total: 4, tone: 'done' },
      { label: '변경 제안 검토', done: 1, total: 4, tone: 'review' },
    ])
  })
})

const monitor = (patch: Partial<WorkMonitor>): WorkMonitor => ({
  owner: '박팀원',
  agent: '박팀원의 에이전트',
  status: '작업 중',
  screen: 'working',
  tone: 'working',
  title: '로그인 화면',
  taskLabel: 'TASK-003 · 진행 중',
  action: '테스트 실행',
  files: ['src/login.tsx', 'src/login.test.tsx'],
  detail: '',
  at: '2030-05-15T03:04:00Z',
  target: { type: 'task', id: 'TASK-003' },
  ...patch,
})

describe('에이전트 책상 모니터', () => {
  it('책상마다 늘 같은 코딩 앱이고, 어느 외관 사무실에나 다섯 앱이 모두 있다', () => {
    const designs = ['glass', 'wood', 'tower', 'brick', 'modern']
    for (const design of designs) {
      // 인원 객체를 넘겨야 고정 사무실이다(D36).
      const floor = makeDreamFloor(
        makeBuilding(ALL_KEYS, design, undefined, {}).floors[1],
        4,
      )
      const desks = floor.furniture
        .filter((item) => item.tag?.startsWith('workstation/'))
        .map(officeFurnitureKey)
      expect(desks.length).toBeGreaterThan(3)
      for (const desk of desks)
        expect(codingAppOf(desk)).toBe(codingAppOf(desk))
      expect(new Set(desks.map(codingAppOf)), design).toEqual(
        new Set(['claude', 'vscode', 'intellij', 'terminal', 'browser']),
      )
    }
  })

  it('작업 중인 에이전트 화면은 실제 작업·파일·실행 기록으로 채운다', () => {
    const screen = codingScreen(monitor({}), 'desk/3/4', '우리 프로젝트')
    expect(screen).toMatchObject({
      app: codingAppOf('desk/3/4'),
      mode: 'working',
      title: 'TASK-003 · 진행 중 · 로그인 화면',
      who: '박팀원의 에이전트',
      state: '작업 중',
      files: ['src/login.tsx', 'src/login.test.tsx'],
      activity: '테스트 실행',
      taskKey: 'TASK-003',
      label: 'TASK-003 · 진행 중',
      area: 'src',
      lang: 'TypeScript JSX',
      steps: [
        ['계획 등록', true],
        ['작업 시작', true],
        ['완료 보고', false],
      ],
    })
    expect(screen.at).toMatch(/^\d{2}:\d{2}$/)
  })

  it('Claude Code 화면 줄은 실제 파일로 도구 호출을 쓰고, 터미널은 작업 번호로 커밋 줄을 쓴다', () => {
    const working = codingScreen(monitor({}), 'x', 'p')
    expect(
      codingLines({ ...working, app: 'claude' }).map((line) => line.text),
    ).toEqual([
      '> TASK-003 · 진행 중 · 로그인 화면',
      '⏺ Read(src/login.tsx)',
      '  ⎿ 테스트 실행',
      '⏺ Update(src/login.test.tsx)',
      '+   const saved = await save(draft)',
      '-   const saved = save(draft)',
      '☒ 계획 등록  ☒ 작업 시작  ☐ 완료 보고',
      '✻ 작업 중…',
    ])
    const terminal = codingLines({ ...working, app: 'terminal' })
    expect(terminal.map((line) => line.tone)).toEqual([
      'command',
      'log',
      'command',
      'changed',
      'changed',
      'muted',
      'accent',
    ])
    expect(terminal[1].text).toMatch(/^[0-9a-f]{7} TASK-003 로그인 화면$/)
    expect(terminal[3].text).toBe(' M src/login.tsx')
    expect(terminal[5].text).toBe('# 최근 실행: 테스트 실행')
    // 쉬는 화면은 기다리는 줄만, 편집기·브라우저는 줄 대신 제자리(탭·상태 줄)에 그린다.
    const idle = codingScreen(
      monitor({ screen: 'idle', status: '대기' }),
      'x',
      'p',
    )
    expect(
      codingLines({ ...idle, app: 'terminal' }).map((line) => line.text),
    ).toEqual([
      'git log --oneline -1',
      expect.stringMatching(/ TASK-003 로그인 화면$/),
      '# 대기 · 작업을 기다리고 있어요',
    ])
    expect(codingLines({ ...working, app: 'vscode' })).toEqual([])
  })

  it('작업이 없거나 쉬는 에이전트는 대기 화면, 연결 전이면 잠금 화면이다', () => {
    const idle = codingScreen(
      monitor({
        status: '연결됨',
        screen: 'idle',
        title: '배정된 작업 없음',
        taskLabel: '배정된 작업 없음',
        action: '아직 구체적인 실행 기록이 없어요',
        files: [],
        at: null,
        target: { type: 'agent', id: 'member:26' },
      }),
      'desk/3/4',
      '우리 프로젝트',
    )
    expect(idle).toMatchObject({
      mode: 'idle',
      title: '배정된 작업 없음',
      files: [],
      tree: ['README.md'],
      at: '',
    })
    expect(
      codingScreen(monitor({ screen: 'locked', status: '연결 전' }), 'x', 'p')
        .mode,
    ).toBe('locked')
    // 작업 중인데 파일이 아직 보고되지 않았으면 파일을 지어내지 않는다.
    expect(codingScreen(monitor({ files: [] }), 'x', 'p').tree).toEqual([])
    // 팀원 자리가 아닌 화면(옛 배치)은 주인 이름을 쓴다.
    expect(
      codingScreen(monitor({ agent: undefined, screen: undefined }), 'x', 'p'),
    ).toMatchObject({ mode: 'idle', who: '박팀원', state: '작업 중' })
  })
})
