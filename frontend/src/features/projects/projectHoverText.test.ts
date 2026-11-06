import { describe, expect, it } from 'vitest'
import type { RequiredDocument } from '@/api/contracts/common'
import { nextStepText, peopleText } from './projectHoverText'

const documents = (uploaded: number, approved: number) =>
  (['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const).map(
    (kind, index): RequiredDocument => ({
      kind,
      uploaded: index < uploaded,
      approved: index < approved,
    }),
  )

describe('nextStepText', () => {
  it('아직 안 올린 문서가 있으면 팀장과 팀원 모두 초안을 올린다', () => {
    expect(nextStepText(documents(2, 0), true)).toBe('다음: 문서 초안 올리기')
    expect(nextStepText(documents(2, 0), false)).toBe('다음: 문서 초안 올리기')
  })

  it('4종을 다 올렸으면 팀장은 승인하고 팀원은 기다린다', () => {
    expect(nextStepText(documents(4, 1), true)).toBe(
      '다음: 비비 검토 후 승인하기',
    )
    expect(nextStepText(documents(4, 1), false)).toBe(
      '다음: 팀장 승인 기다리기',
    )
  })

  it('4종을 다 승인했으면 팀장이 프로젝트를 시작한다', () => {
    expect(nextStepText(documents(4, 4), true)).toBe('다음: 프로젝트 시작하기')
    expect(nextStepText(documents(4, 4), false)).toBe(
      '다음: 팀장이 프로젝트 시작',
    )
  })
})

describe('peopleText', () => {
  const connected = { agent_connected: true }
  const none = { agent_connected: false }

  it('여러 명이면 인원과 연결된 에이전트 수를 보인다', () => {
    expect(peopleText([connected, none, connected, none])).toBe(
      '4명 · 에이전트 2',
    )
  })

  it('혼자면 혼자 사용으로 보이고 에이전트가 있으면 덧붙인다', () => {
    expect(peopleText([none])).toBe('혼자 사용')
    expect(peopleText([connected])).toBe('혼자 사용 · 에이전트 1')
  })
})
