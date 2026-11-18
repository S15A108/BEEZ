import { useQuery } from '@tanstack/react-query'
import { documentsApi } from '@/api/services'

// 문서 ID를 사람이 읽는 제목으로. 공용 문서함·기획 분석과 같은 목록(키)을 나눠 쓰고, 못 찾으면 번호 대신 '문서'다.
export function useDocumentTitle(projectId: string, enabled = true) {
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
    enabled,
  })
  return (id: string) =>
    documents.data?.items.find((document) => document.id === id)?.title ??
    '문서'
}
