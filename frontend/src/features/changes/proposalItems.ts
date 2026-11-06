import { z } from 'zod'
import type {
  DocumentChange,
  ResolvedDocumentChange,
} from '@/api/contracts/common'
import { documentTextError } from './normalizeDocument'

export const kindLabel = {
  REQUIREMENT: '요구사항',
  API_CONTRACT: 'API 계약',
  DATA_CONTRACT: '데이터 계약',
  POLICY: '정책',
  CONSTRAINT: '제약',
} as const
export const operationLabel = {
  UPDATE: '고치기',
  CREATE: '새 항목',
  RETIRE: '없애기',
} as const

// 사람이 쓰는 변경 제안은 문서 기반이다(사용자 결정 11-02): 공용 문서함의 '고치기'로 문서 하나를 고쳐 올린다.
// 에이전트·회의가 만든 제안의 설계 항목 변경은 보기만 하고, 다시 낼 때 받은 그대로 보낸다.
export const proposalFormSchema = z
  .object({
    title: z
      .string()
      .trim()
      .min(1, '제목을 입력해 주세요.')
      .max(120, '120자까지 쓸 수 있어요.'),
    reason: z
      .string()
      .trim()
      .min(1, '이유를 입력해 주세요.')
      .max(2000, '2000자까지 쓸 수 있어요.'),
    task_id: z.string(),
    // 고칠 문서: 고친 글을 after_content로 보낸다(CHG-A01 documents).
    document_id: z.string().optional(),
    after_content: z.string().optional(),
    // 고친 글을 기준 원문으로 되돌려 이번 제안에서 뺀 문서. 원래 제안의 같은 문서 변경도 함께 뺀다(ProposalForm이 채운다).
    removed_document_id: z.string().optional(),
    // 다시 내는 제안에서 폼 밖에 그대로 남는 문서 변경 수(toDocumentChanges의 kept)와 설계 항목 변경 수
    kept_documents: z.number().int().optional(),
    kept_items: z.number().int().optional(),
    // 화면이 보여 준 기준 원문과 그 버전, 그리고 가장 최근에 받은 기준 버전(ProposalForm이 채운다)
    base_content: z.string().optional(),
    base_version_id: z.string().optional(),
    latest_version_id: z.string().optional(),
  })
  .superRefine((form, context) => {
    if (!form.document_id && !form.kept_documents && !form.kept_items)
      context.addIssue({
        code: 'custom',
        path: ['document_id'],
        message: '고칠 문서를 골라 주세요.',
      })
    if (form.document_id && !form.after_content?.trim())
      context.addIssue({
        code: 'custom',
        path: ['after_content'],
        message: '고친 글을 입력해 주세요.',
      })
    // 직접 쓴 글도 서버가 같은 규칙으로 거절하므로 올리기 전에 막는다(PR #135 리뷰).
    const textError =
      form.document_id && documentTextError(form.after_content ?? '')
    if (textError)
      context.addIssue({
        code: 'custom',
        path: ['after_content'],
        message: textError,
      })
  })
export type ProposalFormValues = z.infer<typeof proposalFormSchema>

// 다시 내는 제안의 문서 변경 중 기준 버전이 지금 승인본과 다른 것(그 사이 다른 변경이 승인됨)은 그대로 낼 수 없다.
// 그대로 내면 승인된 변경을 되돌리거나 409가 나므로, 새 기준에서 다시 쓰게 따로 나눈다.
export function isStaleDocument(
  change: Pick<
    ResolvedDocumentChange,
    'document_id' | 'base_document_version_id'
  >,
  currentVersionOf: (documentId: string) => string | undefined,
) {
  return (
    currentVersionOf(change.document_id) !== change.base_document_version_id
  )
}

// 제안의 문서 변경. 폼은 문서 하나(document_id)를 고치고, 원래 제안의 나머지 문서 변경은 받은 값 그대로 함께 보낸다.
// 폼에서 같은 문서를 고르면 폼의 글이 그 문서를 대신한다.
// 기준 원문으로 되돌린 문서(removed_document_id)는 원래 제안에 있던 변경도 빼고 보내지 않는다(PR #72 재전수 ROUND2-DOC-01).
export function toDocumentChanges(
  form: Pick<
    ProposalFormValues,
    'document_id' | 'after_content' | 'removed_document_id'
  >,
  editedVersionId: string | undefined,
  kept: ResolvedDocumentChange[] = [],
): DocumentChange[] {
  // 기준 버전을 모르는 채 고친 문서를 빼고 보내지 않는다(폼이 먼저 막는다).
  if (form.document_id && !editedVersionId)
    throw new Error('고친 문서의 기준 버전을 알 수 없어요.')
  return [
    ...(form.document_id && editedVersionId
      ? [
          {
            document_id: form.document_id,
            base_document_version_id: editedVersionId,
            after_content: form.after_content ?? '',
          },
        ]
      : []),
    ...kept
      .filter(
        (change) =>
          change.document_id !== form.document_id &&
          change.document_id !== form.removed_document_id,
      )
      .map((change) => ({
        document_id: change.document_id,
        base_document_version_id: change.base_document_version_id,
        after_content: change.after_content,
      })),
  ]
}
