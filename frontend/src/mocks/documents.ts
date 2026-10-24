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
  requiredString,
  strings,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { copy, nextId, now } from './state'
import type { MockProject } from './state'

export function draftSnapshotHash(project: MockProject): string {
  return `sha256:mock-drafts-${project.project.id}-${project.items
    .filter((item) => item.status === 'DRAFT')
    .map((item) => item.id)
    .sort()
    .join('-')}`
}

function analyzeDocument(context: MockContext, version: DocumentVersion): void {
  const project = requireProject(context)
  if (
    project.items.some(
      (item) => item.source?.document_version_id === version.id,
    )
  )
    return
  const source = {
    document_id: version.document_id,
    document_version_id: version.id,
    version_no: version.version_no,
    location: '1. 예시 분석',
  }
  const document = findById(project.documents, version.document_id)
  project.items.push({
    id: nextId(context.database),
    design_item_id: nextId(context.database),
    key: `REQ-${String(project.items.length + 1).padStart(3, '0')}`,
    kind: 'REQUIREMENT',
    version_no: 1,
    status: 'DRAFT',
    schema_version: 1,
    content: {
      description:
        version.content
          ?.split('\n')
          .find((line) => line.trim() && !line.startsWith('#')) ??
        document.title,
    },
    source,
    content_hash: `sha256:mock-${version.id}`,
  })
  project.questions.push({
    id: nextId(context.database),
    question: `${document.title}의 완료 기준을 어떻게 확인하나요? (규칙 기반 예시)`,
    source,
  })
}

async function textOf(file: Blob): Promise<string> {
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
  if (file.size > 10 * 1024 * 1024)
    fail(413, 'file_too_large', '예시 파일은 10MB 이하여야 해요.')
  const fileName = file instanceof File ? file.name : 'document.md'
  if (!/\.(md|markdown|txt)$/i.test(fileName))
    fail(
      415,
      'unsupported_media_type',
      '브라우저 예시는 텍스트·마크다운 원문만 처리해요.',
    )
  const content = await textOf(file)
  if (!content.trim())
    fail(400, 'validation_failed', '원문 내용이 비어 있어요.')
  let document = existingId
    ? findById(project.documents, existingId)
    : undefined
  if (document) {
    const base = String(context.form?.get('base_document_version_id') ?? '')
    expectValue(document.approved_version?.id, base)
  } else {
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
    created_at: now(),
  })
  project.versions.push(version)
  document.latest_draft = version
  analyzeDocument(context, version)
  activity(context, 'DOCUMENT_UPLOADED')
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
  const project = requireProject(context)
  const { body, params, query, database } = context
  switch (id) {
    case 'DOC-A01':
      return upload(context)
    case 'DOC-A12':
      return upload(context, params.document_id)
    case 'DOC-A02':
      return {
        data: page(
          project.documents.filter(
            (document) =>
              (!query.get('kind') || document.kind === query.get('kind')) &&
              (!query.get('domain') || document.domain === query.get('domain')),
          ),
          query,
        ),
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
        },
      }
    }
    case 'PLN-A03':
      return {
        data: page(
          project.questions.filter(
            (question) =>
              !query.get('document_version_id') ||
              question.source?.document_version_id ===
                query.get('document_version_id'),
          ),
          query,
        ),
      }
    case 'PLN-A04': {
      const question = findById(project.questions, params.question_id)
      question.answer = requiredString(body, 'answer')
      question.answered_by = requireAccount(context).id
      question.answered_at = now()
      return { data: question }
    }
    case 'PLN-A05': {
      const ids = strings(body.document_version_ids)
      if (!ids.length)
        fail(400, 'validation_failed', '초안의 원문 버전을 선택해 주세요.')
      ids.forEach((versionId) =>
        analyzeDocument(context, findById(project.versions, versionId)),
      )
      const sourceIds = new Set(ids)
      const previousDrafts = project.items.filter(
        (item) =>
          item.status === 'DRAFT' &&
          item.source &&
          sourceIds.has(item.source.document_version_id),
      )
      for (const previous of previousDrafts) {
        const answers = project.questions
          .filter(
            (question) =>
              question.source?.document_version_id ===
                previous.source?.document_version_id && question.answer,
          )
          .map((question) => `${question.question} ${question.answer}`)
        const revised: DesignItemVersion = {
          ...copy(previous),
          id: nextId(database),
          version_no: previous.version_no + 1,
          content_hash: `sha256:mock-draft-${database.next_id}`,
          content: {
            ...previous.content,
            ...(answers.length ? { clarification: answers.join('\n') } : {}),
          },
        }
        previous.status = 'SUPERSEDED'
        project.items.push(revised)
      }
      return {
        status: 202,
        data: { status: 'PENDING', document_version_ids: ids },
      }
    }
    case 'PLN-A06':
      return {
        data: {
          ...page(
            project.items.filter(
              (item) =>
                (!query.get('status') || item.status === query.get('status')) &&
                (!query.get('kind') || item.kind === query.get('kind')) &&
                (!query.get('document_version_id') ||
                  item.source?.document_version_id ===
                    query.get('document_version_id')),
            ),
            query,
          ),
          draft_snapshot_hash: draftSnapshotHash(project),
        },
      }
    case 'PLN-A07': {
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
          'baseline_already_exists',
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
      versions.forEach((version) => {
        version.status = 'APPROVED'
        const document = findById(project.documents, version.document_id)
        document.approved_version = version
        if (document.latest_draft?.id === version.id)
          delete document.latest_draft
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
      activity(context, 'BASELINE_PUBLISHED', { baseline_id: baseline.id })
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
