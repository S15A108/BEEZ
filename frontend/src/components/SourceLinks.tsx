import type { Source } from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { Button } from '@/components/ui/button'
import { citationFocus, type CitationTarget } from '@/lib/citationTarget'
import { lineRangeText } from '@/lib/display'

export function SourceLinks({
  sources,
  citation,
}: {
  sources: Source[]
  citation?: CitationTarget
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  return (
    <div className="mt-3 flex flex-wrap gap-2">
      {sources.map((source, index) => (
        <Button
          key={`${source.document_version_id}:${index}`}
          variant="outline"
          size="sm"
          disabled={
            !!citation &&
            (citation.document_id !== source.document_id ||
              citation.document_version_id !== source.document_version_id)
          }
          // 문서 ID만 넘기면 승인본이 열린다. 인용한 그 버전(초안일 수 있다)을 열게 버전 ID도 넘긴다.
          onClick={() =>
            openPane(
              'documents',
              citation &&
                citation.document_id === source.document_id &&
                citation.document_version_id === source.document_version_id
                ? citationFocus(citation)
                : `version:${source.document_id}:${source.document_version_id}`,
            )
          }
        >
          문서 {source.document_id} v{source.version_no} ·{' '}
          {lineRangeText(source.location)}
        </Button>
      ))}
    </div>
  )
}
