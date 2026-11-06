/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi, beeApi } from '../api/services'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'
import { loadMockDatabase, saveMockDatabase } from './state'
import type { MockProject } from './state'

// BEE-A04 브라우저 예시: 서버 BeeRepository.latestActivity처럼 10분 안의 비비 검토와 OPEN 회의 중 가장 최근 하나.
let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => restore())

function editProject(change: (project: MockProject) => void) {
  const database = loadMockDatabase()
  change(database.projects.find((item) => item.project.id === '101')!)
  saveMockDatabase(database)
}
const minutesAgo = (minutes: number) =>
  new Date(Date.now() - minutes * 60_000).toISOString()

it('검토 중인 문서가 없으면 열린 회의를 "\'제목\' 진행 중"으로 알려 준다', async () => {
  await expect(beeApi.status('101')).resolves.toEqual({
    activity: 'MEETING',
    target_type: 'MEETING',
    target_id: '901',
    label: "'초대 안내 문구 검토' 진행 중",
  })
})

it('회의보다 늦게 시작한 비비 검토가 있으면 그 문서를 "문서 이름 검토 중"으로 알려 준다', async () => {
  let document = { id: '', title: '' }
  editProject((project) => {
    document = project.documents[0]
    project.running_bee_reviews = [
      { document_id: document.id, started_at: minutesAgo(1) },
    ]
  })
  await expect(beeApi.status('101')).resolves.toEqual({
    activity: 'DOCUMENT_REVIEW',
    target_type: 'DOCUMENT',
    target_id: document.id,
    label: `${document.title} 검토 중`,
  })
})

it('시작한 지 10분이 지난 검토는 멈춘 것으로 보고 빼고, 회의도 없으면 한가하다', async () => {
  editProject((project) => {
    project.running_bee_reviews = [
      { document_id: project.documents[0].id, started_at: minutesAgo(11) },
    ]
  })
  await expect(beeApi.status('101')).resolves.toMatchObject({
    activity: 'MEETING',
  })
  editProject((project) => {
    project.meetings.forEach((meeting) => (meeting.status = 'CLOSED'))
  })
  await expect(beeApi.status('101')).resolves.toEqual({ activity: 'IDLE' })
})
