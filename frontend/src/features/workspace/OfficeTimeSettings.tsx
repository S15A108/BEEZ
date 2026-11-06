import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { OfficeTime } from '@/three/office/officeLook'

const options: { value: 'auto' | OfficeTime; label: string; hint: string }[] = [
  {
    value: 'auto',
    label: '자동',
    hint: '한국 시각에 맞춰요. 7시부터 낮, 17시부터 노을, 19시부터 밤',
  },
  { value: 'day', label: '낮', hint: '햇빛이 드는 밝은 사무실' },
  {
    value: 'sunset',
    label: '노을',
    hint: '창밖이 붉게 물들고 천장 등이 켜져요',
  },
  { value: 'night', label: '밤', hint: '창밖이 어둡고 천장 등만 밝아요' },
]

// 설정 > 내 설정 > 사무실 화면(D39). 사무실은 프로젝트 건물 외관을 따르고, 시간대는 이 브라우저에만 저장한다.
export function OfficeTimeSettings() {
  const value = useWorkspaceStore((state) => state.officeTime)
  const setValue = useWorkspaceStore((state) => state.setOfficeTime)
  return (
    <section className="space-y-6">
      <header>
        <h2 className="text-xl font-bold">사무실 화면</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          사무실은 프로젝트 건물 외관에 맞춰 꾸며져요. 시간대는 이 브라우저에만
          저장돼요.
        </p>
      </header>
      <fieldset className="grid gap-2.5">
        <legend className="mb-2.5 text-sm font-medium">시간대</legend>
        <div className="grid grid-cols-2 gap-2.5 lg:grid-cols-4">
          {options.map((item) => {
            const checked = value === item.value
            return (
              <label
                key={item.value}
                className={cn(
                  'relative grid cursor-pointer content-start gap-1 rounded-[14px] border border-border bg-white px-3 py-2.5 has-focus-visible:ring-2 has-focus-visible:ring-ring',
                  checked &&
                    'border-2 border-[#c99e1e] bg-[#fffaeb] px-[11px] py-[9px]',
                )}
              >
                <input
                  type="radio"
                  name="office-time"
                  className="sr-only"
                  checked={checked}
                  onChange={() => setValue(item.value)}
                />
                <span className="text-sm font-semibold">{item.label}</span>
                <span className="text-xs leading-[17px] break-keep text-[#6b6658]">
                  {item.hint}
                </span>
                {checked && (
                  <span
                    aria-hidden="true"
                    className="absolute top-2 right-2 grid size-5 place-items-center rounded-full bg-primary text-primary-foreground"
                  >
                    <Check className="size-3" strokeWidth={3.5} />
                  </span>
                )}
              </label>
            )
          })}
        </div>
      </fieldset>
    </section>
  )
}
