import { statusLabel } from '@/lib/display'
import { Tag } from './Tag'
import { statusTone } from './tagTones'

export function StatusTag({ value }: { value: string }) {
  return <Tag tone={statusTone[value]}>{statusLabel(value)}</Tag>
}
