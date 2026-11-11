import { useQuery } from '@tanstack/react-query'
import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'
import { draftWork, hasActiveJob } from '@/features/planning/draftJobs'
import { getApiErrorCode } from '@/api/error'
import { documentsApi, planningApi } from '@/api/services'
import type { DesignItemVersion } from '@/api/contracts/common'
import { useProjectAction } from '@/features/workspace/useProjectAction'

const REQUIRED = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']

// 시작(PLN-A10)은 초안 전체를 정확히 보내야 해서 모든 페이지를 같은 draft_snapshot_hash로 읽는다.
// 읽는 사이 초안이 바뀌어 해시가 달라지거나, 서버가 뒤 페이지를 409 draft_changed로 거절하면(cursor에 해시가 담김)
// 처음부터 다시 읽는다. 모두 세 번까지 읽고 멈춘다(PR #72 mock/live 전수 대조 DOC-LIVE-4).
export async function readAllDrafts(projectId: string) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const items: DesignItemVersion[] = []
    let hash: string | undefined
    let cursor: string | undefined
    let consistent = true
    do {
      let page: Awaited<ReturnType<typeof planningApi.items>>
      try {
        page = await planningApi.items(projectId, {
          status: 'DRAFT',
          limit: 100,
          cursor,
        })
      } catch (cause) {
        if (!cursor || getApiErrorCode(cause) !== 'draft_changed') throw cause
        consistent = false
        break
      }
      hash ??= page.draft_snapshot_hash
      if (page.draft_snapshot_hash !== hash) {
        consistent = false
        break
      }
      items.push(...page.items)
      cursor = page.has_more ? page.next_cursor : undefined
    } while (cursor)
    if (consistent) return { items, draft_snapshot_hash: hash }
  }
  throw new Error('설계 초안이 바뀌는 중이에요. 잠시 뒤 다시 확인해 주세요.')
}

// 프로젝트 시작 = 최초 기준 설계 생성(PLN-A10). 필수 문서 4종의 승인본과 설계 항목 초안으로 기준 v1을 만든다.
// 시작 가이드 3단계와 공용 문서함의 '프로젝트 시작'이 같은 버튼이다(SCR-06·PNL-02 수정안 10-29).
export function useProjectStart(projectId: string, enabled = true) {
  const action = useProjectAction(projectId)
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
    enabled,
  })
  const drafts = useQuery({
    queryKey: ['projects', projectId, 'start-drafts'],
    queryFn: () => readAllDrafts(projectId),
    enabled,
  })
  // 후보 작업(PLN-A05). 기획 분석 창과 같은 키라 한쪽에서 받은 것을 같이 쓴다.
  const jobs = useQuery({
    queryKey: ['projects', projectId, 'design-draft-jobs'],
    queryFn: () => planningApi.drafts(projectId),
    enabled,
    refetchInterval: (query) =>
      !query.state.error && hasActiveJob(query.state.data?.jobs ?? [])
        ? ACTIVE_POLL_MS
        : false,
  })
  const approved =
    documents.data?.items.filter(
      (document) =>
        document.approved_version && REQUIRED.includes(document.kind),
    ) ?? []
  const approvedCount = new Set(approved.map((document) => document.kind)).size
  // API 없음: 문서별 색인 완료(PRJ-A06 required_documents[].index_ready)는 PR #42 요청 중이라
  // 설계 항목 초안이 있는지로 대신 본다.
  const indexed =
    !!drafts.data?.draft_snapshot_hash && drafts.data.items.length > 0
  return {
    approvedCount,
    indexed,
    // 서버는 문서 승인·색인 뒤에 설계 초안을 만들지 않는다. 초안이 0개면 기획 분석 창에서 만들어야 시작할 수 있다.
    needsDrafts: drafts.data?.items.length === 0,
    ready: approvedCount === 4 && indexed,
    // 설계 초안이 있어도 후보가 남아 있으면 기획 분석 창으로 돌아가 이어서 하게 한다.
    work: draftWork(jobs.data?.jobs ?? []),
    busy: action.busy,
    error: action.error,
    start: () =>
      action.run(
        () =>
          planningApi.publishBaseline({
            project_id: projectId,
            document_version_ids: approved.map(
              (document) => document.approved_version!.id,
            ),
            design_item_version_ids: drafts.data!.items.map((item) => item.id),
            draft_snapshot_hash: drafts.data!.draft_snapshot_hash!,
          }),
        '프로젝트를 시작했어요.',
      ),
  }
}
