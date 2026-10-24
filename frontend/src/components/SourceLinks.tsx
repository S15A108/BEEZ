import type { Source } from '@/api/contracts/common'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { Button } from '@/components/ui/button'

export function SourceLinks({ sources }: { sources: Source[] }) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  return (
    <div className="mt-3 flex flex-wrap gap-2">
      {sources.map((source, index) => (
        <Button
          key={`${source.document_version_id}:${index}`}
          variant="outline"
          size="sm"
          onClick={() => openPane('documents', source.document_id)}
        >
          문서 {source.document_id} v{source.version_no} · {source.location}
        </Button>
      ))}
    </div>
  )
}
