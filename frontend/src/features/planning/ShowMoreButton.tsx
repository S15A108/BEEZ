import { Button } from '@/components/ui/button'

export function ShowMoreButton({
  hidden,
  label,
  onClick,
  busy = false,
}: {
  hidden: number
  label: string
  onClick: () => void
  busy?: boolean
}) {
  if (hidden <= 0) return null
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="justify-self-start"
      disabled={busy}
      onClick={onClick}
    >
      {label} 더 보기 ({hidden}개 남음)
    </Button>
  )
}
