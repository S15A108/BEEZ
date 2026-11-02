import { useState } from 'react'
import type { ReactNode } from 'react'
import { ActionForm } from '@/components/ActionForm'
import type { FormField } from '@/components/ActionForm'
import { PaneSheet } from '@/components/PaneSheet'

// 업무 창 안에서는 '‹ 뒤로' 하위 화면으로, 그 밖에서는 작은 창으로 여는 입력 폼.
export function FormDialog({
  title,
  description,
  back,
  fields,
  onSubmit,
  onClose,
  submitLabel = '저장',
  destructive,
  columns,
  children,
}: {
  title: string
  description?: string
  /** 하위 화면 머리줄 '‹' 옆에 둘 돌아갈 곳 이름 */
  back?: string
  fields: FormField[]
  onSubmit: (values: Record<string, string>) => Promise<unknown>
  onClose: () => void
  submitLabel?: string
  destructive?: boolean
  /** 넓은 화면에서 두 칸 배치(ActionForm columns) */
  columns?: boolean
  /** 설명과 입력 칸 사이에 둘 내용(예: 넘길 작업 목록) */
  children?: ReactNode
}) {
  const [busy, setBusy] = useState(false)
  return (
    <PaneSheet
      title={title}
      description={description}
      back={back}
      busy={busy}
      onClose={onClose}
    >
      {children}
      <ActionForm
        fields={fields}
        onSubmit={onSubmit}
        submitLabel={submitLabel}
        onBusyChange={setBusy}
        destructive={destructive}
        columns={columns}
      />
    </PaneSheet>
  )
}
