import { ActionForm } from '@/components/ActionForm'

// 작성 내용을 직접 고치는 폼. 최종본 확인(3단계)의 '다시 고치기'와 비비 검토가 실패했을 때 앞 단계로 돌아가 고치는 화면이 같이 쓴다.
// 저장하면 리비전이 올라 이전 검토는 무효가 되고 비비 검토가 처음부터 다시 시작한다(DOC-A14).
export function WorkingCopyEditor({
  content,
  onSave,
  onCancel,
}: {
  content: string
  onSave: (content: string) => Promise<unknown>
  onCancel?: () => void
}) {
  return (
    <ActionForm
      fields={[
        {
          name: 'content',
          label: '본문',
          type: 'textarea',
          required: true,
          value: content,
        },
      ]}
      submitLabel="고친 내용 저장"
      onCancel={onCancel}
      onSubmit={(values) => onSave(values.content)}
    />
  )
}
