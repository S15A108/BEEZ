import {
  baselineSchema,
  designItemVersionSchema,
  documentSchema,
  documentVersionSchema,
  sourceSchema,
} from '../api/contracts/common'
import type {
  Baseline,
  DesignItemVersion,
  DocumentVersion,
} from '../api/contracts/common'
import {
  activity,
  expectValue,
  fail,
  findById,
  page,
  requireAccount,
  requireLeader,
  requireProject,
  strings,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { copy, nextId, now } from './state'
import type { MockProject } from './state'
import { reviewRoutes } from './documentReviews'
import { planningDraftRoutes, questionGeneration } from './planningDrafts'

export function draftSnapshotHash(project: MockProject): string {
  return `sha256:mock-drafts-${project.project.id}-${project.items
    .filter((item) => item.status === 'DRAFT')
    .map((item) => `${item.id}.${item.content_hash ?? ''}`)
    .sort()
    .join('-')}`
}

const sourceOf = (version: DocumentVersion) => ({
  document_id: version.document_id,
  document_version_id: version.id,
  version_no: version.version_no,
  location: '1. 예시 분석',
})

// 원문 분석(색인)은 보완 질문만 남긴다. 실제 서버처럼 문서 등록·승인·색인만으로는 설계 초안을 만들지 않는다
// (PR #72 전수 리뷰 AMP-01). 초안은 기획 분석 창의 초안 만들기(PLN-A05)나 직접 추가(PLN-A07)로만 생긴다.
export function analyzeDocument(
  context: MockContext,
  version: DocumentVersion,
): void {
  const project = requireProject(context)
  if (
    project.questions.some(
      (question) => question.source?.document_version_id === version.id,
    )
  )
    return
  const document = findById(project.documents, version.document_id)
  project.questions.push({
    id: nextId(context.database),
    question: `${document.title}의 완료 기준을 어떻게 확인하나요? (규칙 기반 예시)`,
    kind: 'CLARIFICATION',
    question_type: 'SHORT_ANSWER',
    answer_format: { type: 'SHORT_ANSWER', max_length: 200 },
    status: 'OPEN',
    evidence_status: 'REVIEW_CANDIDATE',
    source: sourceOf(version),
  })
}

export async function textOf(file: Blob): Promise<string> {
  if (typeof file.text === 'function') return file.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(new Error('파일 내용을 읽지 못했어요.'))
    reader.readAsText(file)
  })
}

async function upload(
  context: MockContext,
  existingId?: string,
): Promise<MockReply> {
  const project = requireProject(context)
  const file = context.form?.get('file')
  if (!(file instanceof Blob))
    fail(400, 'validation_failed', '원문 파일을 선택해 주세요.')
  if (file.size > 1024 * 1024)
    fail(413, 'file_too_large', '파일은 1 MiB 이하여야 해요.')
  const fileName = file instanceof File ? file.name : 'document.md'
  if (!/\.(md|txt)$/i.test(fileName))
    fail(
      415,
      'unsupported_media_type',
      '브라우저 예시는 텍스트·마크다운 원문만 처리해요.',
    )
  const content = await textOf(file)
  if (!content.trim())
    fail(400, 'validation_failed', '원문 내용이 비어 있어요.')
  if ([...content].length > 100_000)
    fail(413, 'file_too_large', '텍스트는 10만 문자 이하여야 해요.')
  let document = existingId
    ? findById(project.documents, existingId)
    : undefined
  if (document) {
    const base = String(context.form?.get('base_document_version_id') ?? '')
    expectValue(document.approved_version?.id, base)
  } else {
    if (
      project.documents.some((item) => item.kind === context.form?.get('kind'))
    )
      fail(409, 'document_kind_exists', '같은 종류의 문서가 이미 있어요.')
    document = documentSchema.parse({
      id: nextId(context.database),
      project_id: project.project.id,
      kind: context.form?.get('kind'),
      title: context.form?.get('title'),
      ...(context.form?.get('domain')
        ? { domain: context.form.get('domain') }
        : {}),
    })
    project.documents.push(document)
  }
  const version = documentVersionSchema.parse({
    id: nextId(context.database),
    document_id: document.id,
    version_no:
      Math.max(
        0,
        ...project.versions
          .filter((item) => item.document_id === document.id)
          .map((item) => item.version_no),
      ) + 1,
    status: 'DRAFT',
    file_name: fileName,
    media_type: file.type || 'text/markdown',
    content_hash: `sha256:mock-${context.database.next_id}`,
    content,
    created_by: requireAccount(context).id,
    is_current_baseline: false,
    created_at: now(),
  })
  project.versions.push(version)
  document.latest_draft = version
  if (!existingId)
    project.working_copies.push({
      document_id: document.id,
      owner_account_id: requireAccount(context).id,
      revision: 1,
      status: 'EDITING',
      content,
      file_name: fileName,
    })
  analyzeDocument(context, version)
  activity(context, 'DOCUMENT_REGISTERED')
  return {
    status: 201,
    data: existingId ? version : { document, analysis_status: 'PENDING' },
  }
}

export async function documentRoutes(
  id: string,
  context: MockContext,
): Promise<MockReply | undefined> {
  if (!/^(DOC|PLN|TRC)-/.test(id)) return undefined
  if (/^DOC-A(1[3-9]|2[0-4])$/.test(id)) return reviewRoutes(id, context)
  const planning = planningDraftRoutes(id, context)
  if (planning) return planning
  const project = requireProject(context)
  const { body, params, query, database } = context
  switch (id) {
    case 'DOC-A01':
      return upload(context)
    case 'DOC-A12':
      return upload(context, params.document_id)
    case 'DOC-A02': {
      // 백엔드 목록 API처럼 버전 본문(content)은 빼고 준다. 본문은 DOC-A03으로 따로 받는다.
      const withoutContent = <T extends { content?: string }>(version?: T) =>
        version && { ...version, content: undefined }
      return {
        data: page(
          project.documents
            .filter(
              (document) =>
                (!query.get('kind') || document.kind === query.get('kind')) &&
                (!query.get('domain') ||
                  document.domain === query.get('domain')),
            )
            .map((document) => ({
              ...document,
              approved_version: withoutContent(document.approved_version),
              latest_draft: withoutContent(document.latest_draft),
            })),
          query,
        ),
      }
    }
    case 'DOC-A03': {
      const document = findById(project.documents, params.document_id)
      const version = query.has('version_no')
        ? project.versions.find(
            (item) =>
              item.document_id === document.id &&
              item.version_no === Number(query.get('version_no')),
          )
        : document.approved_version
      if (!version)
        fail(
          404,
          'approved_version_not_found',
          '승인본이 없어요. 초안은 버전을 지정해 조회해 주세요.',
        )
      return { data: { document, version } }
    }
    case 'DOC-A04': {
      findById(project.documents, params.document_id)
      const version =
        project.versions.find(
          (item) =>
            item.document_id === params.document_id &&
            item.version_no === Number(params.version_no),
        ) ?? fail(404, 'not_found', '버전을 찾을 수 없어요.')
      return {
        data: new Blob([version.content ?? ''], { type: version.media_type }),
        headers: {
          'content-type': version.media_type,
          'content-disposition': `attachment; filename="${encodeURIComponent(version.file_name)}"`,
        },
      }
    }
    case 'DOC-A05':
      findById(project.documents, params.document_id)
      return {
        data: page(
          project.versions.filter(
            (version) => version.document_id === params.document_id,
          ),
          query,
        ),
      }
    case 'DOC-A06': {
      const versions = project.versions.filter(
        (version) => version.document_id === params.document_id,
      )
      const before =
        versions.find(
          (version) => version.version_no === Number(query.get('from_version')),
        ) ?? fail(404, 'not_found', '이전 버전을 찾을 수 없어요.')
      const after =
        versions.find(
          (version) => version.version_no === Number(query.get('to_version')),
        ) ?? fail(404, 'not_found', '다음 버전을 찾을 수 없어요.')
      return {
        data: {
          from_version: before.version_no,
          to_version: after.version_no,
          format: 'TEXT',
          diff: `- ${(before.content ?? '').replaceAll('\n', '\n- ')}\n+ ${(after.content ?? '').replaceAll('\n', '\n+ ')}`,
          before_hash: before.content_hash,
          after_hash: after.content_hash,
        },
      }
    }
    case 'DOC-A07': {
      const document = findById(project.documents, params.document_id)
      // 서버 requireAuthor: 문서를 등록한 사람만 고치고, 팀장 검토를 요청한 동안은 409(PR #72 4차 조사 FOURTH-DOC-01).
      const copy = project.working_copies.find(
        (item) => item.document_id === document.id,
      )
      if (copy?.owner_account_id !== requireAccount(context).id)
        fail(
          403,
          'document_author_required',
          '등록한 담당자만 문서를 수정할 수 있습니다.',
        )
      if (copy.status === 'SUBMITTED')
        fail(
          409,
          'submission_locked',
          '팀장 검토를 요청한 뒤에는 고칠 수 없어요.',
        )
      document.domain = typeof body.domain === 'string' ? body.domain : ''
      return { data: document }
    }
    case 'PLN-A01':
    case 'PLN-A02': {
      const version = findById(
        project.versions,
        String(
          id === 'PLN-A02'
            ? body.document_version_id
            : query.get('document_version_id'),
        ),
      )
      if (version.document_id !== params.document_id)
        fail(404, 'not_found', '문서 버전을 찾을 수 없어요.')
      analyzeDocument(context, version)
      return {
        status: id === 'PLN-A02' ? 202 : 200,
        data: {
          document_version_id: version.id,
          status: id === 'PLN-A02' ? 'RUNNING' : 'COMPLETED',
          items: project.items.filter(
            (item) => item.source?.document_version_id === version.id,
          ),
          questions: project.questions.filter(
            (question) => question.source?.document_version_id === version.id,
          ),
          question_generation: questionGeneration(id === 'PLN-A02'),
        },
      }
    }
    case 'PLN-A06': {
      // 실제 서버처럼 초안 목록 cursor에 스냅숏 해시를 담고, 다음 페이지를 부를 때 초안이 바뀌었으면 409 draft_changed
      // (DesignItemQueryService, PR #72 mock/live 전수 대조 DOC-LIVE-4).
      const hash = draftSnapshotHash(project)
      const [cursor, cursorHash] = (query.get('cursor') ?? '').split('~')
      if (cursor && cursorHash !== hash)
        fail(
          409,
          'draft_changed',
          '설계 초안이 바뀌었어요. 처음부터 다시 불러와 주세요.',
        )
      const pageQuery = new URLSearchParams(query)
      if (cursor) pageQuery.set('cursor', cursor)
      const result = page(
        project.items.filter(
          (item) =>
            (!query.get('status') || item.status === query.get('status')) &&
            (!query.get('kind') || item.kind === query.get('kind')) &&
            (!query.get('document_version_id') ||
              item.source?.document_version_id ===
                query.get('document_version_id')),
        ),
        pageQuery,
      )
      return {
        data: {
          ...result,
          ...(result.next_cursor
            ? { next_cursor: `${result.next_cursor}~${hash}` }
            : {}),
          draft_snapshot_hash: hash,
        },
      }
    }
    case 'PLN-A07': {
      // 서버(DesignContentValidator)처럼 모든 종류에 description 문자열이 필요하다.
      const content = body.content as Record<string, unknown> | undefined
      if (
        typeof content?.description !== 'string' ||
        !content.description.trim()
      )
        fail(400, 'validation_failed', 'description 문자열이 필요합니다.')
      const sourceVersion =
        typeof body.source_document_version_id === 'string'
          ? findById(project.versions, body.source_document_version_id)
          : undefined
      const item = designItemVersionSchema.parse({
        id: nextId(database),
        design_item_id: nextId(database),
        key: `REQ-${String(project.items.length + 1).padStart(3, '0')}`,
        kind: body.kind,
        schema_version: body.schema_version ?? 1,
        content: body.content,
        version_no: 1,
        status: 'DRAFT',
        content_hash: `sha256:mock-${database.next_id}`,
        ...(sourceVersion
          ? {
              source: {
                document_id: sourceVersion.document_id,
                document_version_id: sourceVersion.id,
                version_no: sourceVersion.version_no,
                location: body.source_location ?? '직접 입력',
              },
            }
          : {}),
      })
      project.items.push(item)
      return { status: 201, data: item }
    }
    case 'PLN-A08': {
      const item =
        project.items.findLast(
          (candidate) =>
            candidate.design_item_id === params.id &&
            candidate.status === 'DRAFT',
        ) ?? fail(404, 'not_found', '수정할 초안을 찾을 수 없어요.')
      expectValue(item.id, body.expected_version_id)
      expectValue(item.content_hash, body.expected_content_hash)
      const updated: DesignItemVersion = {
        ...copy(item),
        id: nextId(database),
        version_no: item.version_no + 1,
        content: body.content as Record<string, unknown>,
        content_hash: `sha256:mock-${database.next_id}`,
      }
      item.status = 'SUPERSEDED'
      project.items.push(updated)
      return { data: updated }
    }
    case 'PLN-A09': {
      const item =
        project.items.findLast(
          (candidate) =>
            candidate.design_item_id === params.id &&
            candidate.status === 'DRAFT',
        ) ?? fail(404, 'not_found', '제외할 초안을 찾을 수 없어요.')
      item.status = 'REJECTED'
      return { status: 204 }
    }
    case 'PLN-A10': {
      requireLeader(context)
      if (project.baselines.length)
        fail(
          409,
          'baseline_exists',
          '기준 설계 변경은 변경 제안으로 진행해 주세요.',
        )
      expectValue(draftSnapshotHash(project), body.draft_snapshot_hash)
      const versions = strings(body.document_version_ids).map((versionId) =>
        findById(project.versions, versionId),
      )
      const kinds = new Set(
        versions.map(
          (version) => findById(project.documents, version.document_id).kind,
        ),
      )
      if (
        !['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'].every((kind) =>
          kinds.has(kind as 'PLAN'),
        )
      )
        fail(
          400,
          'required_documents_missing',
          '기획안·ERD·요구사항·API 명세서 네 종류가 모두 필요해요.',
        )
      // 서버 BaselinePublishService처럼 문서 4종이 모두 팀장 승인을 받고 승인 전 작성 건이 없을 때만 만든다.
      // 이 요청으로 문서를 승인하지 않는다(PR #72 6차 조사 SIXTH-DOC-02).
      if (
        versions.some((version) => version.status !== 'APPROVED') ||
        project.working_copies.some((copy) => copy.status !== 'APPROVED')
      )
        fail(
          409,
          'document_not_approved',
          '문서별 팀장 승인이 모두 완료된 후 기준 설계를 생성할 수 있습니다.',
        )
      const items = strings(body.design_item_version_ids).map((itemId) =>
        findById(project.items, itemId),
      )
      if (!items.length)
        fail(400, 'validation_failed', '검토한 설계 항목을 선택해 주세요.')
      const baseline: Baseline = baselineSchema.parse({
        id: nextId(database),
        project_id: project.project.id,
        version_no: 1,
        documents: versions.map((version) => ({
          document_id: version.document_id,
          document_version_id: version.id,
        })),
        items: items.map((item) => ({
          design_item_id: item.design_item_id,
          design_item_version_id: item.id,
        })),
        approved_by: requireAccount(context).id,
        created_at: now(),
      })
      items.forEach((item) => {
        item.status = 'APPROVED'
      })
      project.baselines.push(baseline)
      project.project.current_baseline = {
        id: baseline.id,
        version_no: baseline.version_no,
        created_at: baseline.created_at,
      }
      activity(context, 'BASELINE_CREATED', { baseline_id: baseline.id })
      return { status: 201, data: baseline }
    }
    case 'DOC-A08':
      return {
        data:
          project.baselines.at(-1) ??
          fail(404, 'baseline_not_found', '아직 발행된 기준 설계가 없어요.'),
      }
    case 'DOC-A09':
      return {
        data: page(
          project.baselines.map(
            ({ id: baselineId, version_no, created_at }) => ({
              id: baselineId,
              version_no,
              created_at,
            }),
          ),
          query,
        ),
      }
    case 'DOC-A10':
      return {
        data:
          project.baselines.find(
            (baseline) => baseline.version_no === Number(params.version_no),
          ) ?? fail(404, 'not_found', '기준 설계를 찾을 수 없어요.'),
      }
    case 'DOC-A11': {
      const text = (query.get('q') ?? '').toLowerCase()
      const hits = project.documents.flatMap((document) => {
        const version = document.approved_version
        if (
          !version ||
          !`${document.title} ${version.content ?? ''}`
            .toLowerCase()
            .includes(text)
        )
          return []
        return [
          {
            id: document.id,
            type: 'DOCUMENT_CHUNK',
            title: document.title,
            snippet: (version.content ?? '').slice(0, 200),
            source: sourceSchema.parse({
              document_id: document.id,
              document_version_id: version.id,
              version_no: version.version_no,
              location: document.title,
            }),
            score: 1,
          },
        ]
      })
      return { data: { ...page(hits, query), elapsed_ms: 0 } }
    }
    case 'TRC-A01':
      return { data: { items: [], candidates: [] } }
    default:
      return undefined
  }
}
