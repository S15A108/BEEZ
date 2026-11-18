import { documentsApi } from '@/api/services/documents'

// 비비 검토(DOC-A16)를 받고 끝난 검토를 돌려준다. 서버는 접수(202)만 하고 워커가 검토하므로,
// 요청 상태를 받으면 작업본을 다시 읽어 결과를 꺼낸다. 예시 어댑터는 작업본을 읽을 때 검토를 끝낸다(S15P31A108-391).
export async function reviewDocument(
  projectId: string,
  documentId: string,
  revision: number,
) {
  const result = await documentsApi.requestBeeReview(projectId, documentId, {
    expected_revision: revision,
  })
  if ('id' in result) return result
  const review = (await documentsApi.workingCopy(projectId, documentId))
    .latest_review
  if (!review) throw new Error('비비 검토가 끝나지 않았어요.')
  return review
}
