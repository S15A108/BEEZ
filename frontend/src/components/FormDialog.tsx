import { useState } from 'react'
import { ActionForm } from '@/components/ActionForm'
import type { FormField } from '@/components/ActionForm'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

export function FormDialog({
  title,
  description,
  fields,
  onSubmit,
  onClose,
  submitLabel = '저장',
}: {
  title: string
  description?: string
  fields: FormField[]
  onSubmit: (values: Record<string, string>) => Promise<unknown>
  onClose: () => void
  submitLabel?: string
}) {
  const [busy, setBusy] = useState(false)
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose()
      }}
    >
      <DialogContent
        className="max-h-[90dvh] overflow-y-auto sm:max-w-xl"
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault()
        }}
        onInteractOutside={(event) => {
          if (busy) event.preventDefault()
        }}
      >
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            {description ?? '입력한 내용을 확인한 뒤 저장해 주세요.'}
          </DialogDescription>
        </DialogHeader>
        <ActionForm
          fields={fields}
          onSubmit={onSubmit}
          submitLabel={submitLabel}
          onBusyChange={setBusy}
        />
      </DialogContent>
    </Dialog>
  )
}
