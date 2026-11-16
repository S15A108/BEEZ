import { useQuery } from '@tanstack/react-query'
import { documentsApi } from '@/api/services/documents'
import type { Activity, Document } from '@/api/contracts/common'

// 문서 활동(대상 DOCUMENT_VERSION)이 가리키는 문서의 이름. 활동 응답에는 이름이 없어 문서 목록에서 그 버전을 찾는다.
// 목록은 문서마다 승인본과 최신 초안만 주므로 지난 버전을 가리키는 활동은 이름을 찾지 못하고, 그때는 이름 없이 둔다.
export function documentTitleOf(
  item: Pick<Activity, 'target_type' | 'target_id'>,
  documents: Document[] = [],
) {
  if (item.target_type !== 'DOCUMENT_VERSION' || !item.target_id) return
  return documents.find(
    (document) =>
      document.approved_version?.id === item.target_id ||
      document.latest_draft?.id === item.target_id,
  )?.title
}

// 공용 문서함·기획 분석과 같은 목록(키)을 나눠 쓴다. 문서 활동이 없으면 부르지 않는다.
export function useActivityDocumentTitle(projectId: string, items: Activity[]) {
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
    enabled: items.some((item) => item.target_type === 'DOCUMENT_VERSION'),
  })
  return (item: Activity) => documentTitleOf(item, documents.data?.items)
}
