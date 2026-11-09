import { useId, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { getApiErrorMessage } from '@/api/error'
import { DISPLAY_COLORS, objectParticle } from '@/lib/display'
import { cn } from '@/lib/utils'
import { SheetSubmit } from '@/components/PaneSheet'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'

export interface FormField {
  name: string
  label: string
  /** chips: 보기 몇 개 중 하나를 둥근 칩으로 고른다(캔버스 PNL-05-v2-wait·record). */
  type?:
    'email' | 'password' | 'textarea' | 'select' | 'chips' | 'text' | 'color'
  /** columns 폼에서 오른쪽 칸에 둔다 */
  column?: 2
  required?: boolean
  /** 다른 칸 값에 따라 필수일 때(예: 검토 요청이면 대상 팀원) */
  requiredWhen?: (values: Record<string, string>) => boolean
  min?: number
  max?: number
  value?: string
  placeholder?: string
  matches?: string
  options?: { value: string; label: string }[]
  /** 추가 규칙. 틀리면 보여 줄 문구를 돌려준다. */
  check?: (value: string) => string | undefined
  /** 입력 칸 오른쪽 위에 글자 수를 보여 주고, max를 넘겨 입력하거나 붙여 넣지 못하게 한다. max가 있어야 한다. */
  counter?: boolean
  /** 틀린 곳이 없을 때 칸 아래에 둘 안내 */
  hint?: string
  /** 입력 칸 대신 이 자리에 그릴 내용(예: 이메일 인증). 값은 폼에 들어가지 않는다. */
  custom?: ReactNode
}
interface Props {
  fields: FormField[]
  onSubmit: (values: Record<string, string>) => Promise<unknown>
  submitLabel: string
  onBusyChange?: (busy: boolean) => void
  /** 입력 칸과 제출 버튼 사이에 넣을 내용 */
  footer?: ReactNode
  /** 제출 버튼을 폼 너비로 늘린다 */
  block?: boolean
  /** 되돌릴 수 없는 동작이면 빨간 버튼 */
  destructive?: boolean
  /** 제출 버튼을 끈다 (확인 체크 전 등) */
  disabled?: boolean
  /** 지금 입력 값을 보고 제출 버튼을 끈다 (바꾼 것이 없을 때 등) */
  disableWhen?: (values: Record<string, string | undefined>) => boolean
  /** 로그인·가입 화면(캔버스 SCR-01): 높이 40 입력칸, 필수 별표 없음, 칸을 벗어나면 바로 검사 */
  auth?: boolean
  /** 넓은 화면에서 두 칸으로 놓는다. column: 2인 칸은 오른쪽으로 간다. */
  columns?: boolean
  /** 다이얼로그 폼: 제출 버튼 왼쪽에 '취소'를 둔다(캔버스 SCR-04-v2-create-project). */
  onCancel?: () => void
  /** 제출 실패 문구. 화면마다 다르게 안내할 오류 코드가 있을 때 넘긴다. 기본은 공통 문구(getApiErrorMessage)다. */
  errorMessage?: (cause: unknown) => string
}

export function ActionForm({
  fields,
  onSubmit,
  submitLabel,
  onBusyChange,
  footer,
  block,
  destructive,
  disabled,
  disableWhen,
  auth,
  columns,
  onCancel,
  errorMessage = getApiErrorMessage,
}: Props) {
  const formId = useId()
  const [error, setError] = useState('')
  const [shown, setShown] = useState<Record<string, boolean>>({})
  const schema = useMemo(
    () =>
      z.record(z.string(), z.string()).superRefine((values, context) => {
        for (const field of fields) {
          if (field.custom) continue
          const value = values[field.name] ?? ''
          if ((field.required || field.requiredWhen?.(values)) && !value.trim())
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: `${field.label}${objectParticle(field.label)} ${field.type === 'chips' || field.type === 'select' ? '골라' : '입력해'} 주세요.`,
            })
          else if (field.min && value.length < field.min)
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: `${field.min}자 이상 입력해 주세요.`,
            })
          else if (field.max && value.length > field.max)
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: `${field.max}자 이하로 입력해 주세요.`,
            })
          else if (
            field.type === 'email' &&
            value &&
            !z.email().safeParse(value).success
          )
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: '이메일 형식을 확인해 주세요.',
            })
          else if (field.matches && value !== values[field.matches])
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: '입력한 비밀번호가 서로 달라요.',
            })
          else if (value && field.check?.(value))
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: field.check(value)!,
            })
        }
      }),
    [fields],
  )
  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<Record<string, string>>({
    resolver: zodResolver(schema),
    mode: auth ? 'onTouched' : 'onSubmit',
    defaultValues: Object.fromEntries(
      fields
        .filter((field) => !field.custom)
        .map((field) => [
          field.name,
          field.value ?? field.options?.[0]?.value ?? '',
        ]),
    ),
  })
  const watched = useWatch({ control })
  const submitDisabled = isSubmitting || disabled || disableWhen?.(watched)
  return (
    // 입력 칸은 안쪽 격자에 둔다. 창 안 하위 화면에서는 폼이 남은 높이를 채워 버튼 줄이 맨 아래로 간다.
    <form
      className="flex flex-col gap-5"
      noValidate
      onSubmit={handleSubmit(async (values) => {
        setError('')
        onBusyChange?.(true)
        try {
          await onSubmit(values)
        } catch (cause) {
          setError(errorMessage(cause))
        } finally {
          onBusyChange?.(false)
        }
      })}
    >
      <div
        className={cn(
          'grid gap-5',
          columns && 'md:grid-flow-dense md:grid-cols-2 md:items-start',
        )}
      >
        {fields.map((field) =>
          field.custom ? (
            <div key={field.name}>{field.custom}</div>
          ) : (
            <div
              key={field.name}
              className={cn(
                'grid gap-2',
                columns &&
                  (field.column === 2 ? 'md:col-start-2' : 'md:col-start-1'),
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <Label
                  id={`${formId}-${field.name}-label`}
                  htmlFor={`${formId}-${field.name}`}
                >
                  {field.label}
                  {field.required && !auth && (
                    <span aria-hidden="true" className="text-destructive">
                      {' '}
                      *
                    </span>
                  )}
                </Label>
                {field.counter && field.max && (
                  <span className="text-xs text-muted-foreground">
                    {(watched[field.name] ?? '').length} / {field.max}
                  </span>
                )}
              </div>
              {field.type === 'textarea' ? (
                <Textarea
                  id={`${formId}-${field.name}`}
                  rows={4}
                  placeholder={field.placeholder}
                  maxLength={field.counter ? field.max : undefined}
                  disabled={isSubmitting}
                  aria-invalid={Boolean(errors[field.name])}
                  {...register(field.name)}
                />
              ) : field.type === 'select' ? (
                <NativeSelect
                  id={`${formId}-${field.name}`}
                  disabled={isSubmitting}
                  aria-invalid={Boolean(errors[field.name])}
                  {...register(field.name)}
                >
                  {field.options?.map((option) => (
                    <NativeSelectOption key={option.value} value={option.value}>
                      {option.label}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              ) : field.type === 'chips' ? (
                <div
                  role="radiogroup"
                  aria-labelledby={`${formId}-${field.name}-label`}
                  aria-invalid={Boolean(errors[field.name])}
                  className="flex flex-wrap gap-1.5"
                >
                  {field.options?.map((option) => (
                    <label
                      key={option.value}
                      className={cn(
                        'cursor-pointer rounded-full border px-3 py-1 text-[13px] has-focus-visible:ring-2 has-focus-visible:ring-ring',
                        watched[field.name] === option.value
                          ? 'border-foreground font-semibold'
                          : 'border-border text-muted-foreground hover:bg-muted/50',
                      )}
                    >
                      <input
                        type="radio"
                        value={option.value}
                        className="sr-only"
                        disabled={isSubmitting}
                        {...register(field.name)}
                      />
                      {option.label}
                    </label>
                  ))}
                </div>
              ) : field.type === 'color' ? (
                <div
                  role="radiogroup"
                  aria-labelledby={`${formId}-${field.name}-label`}
                  className="flex flex-wrap gap-2.5"
                >
                  {[
                    ...DISPLAY_COLORS,
                    ...(watched[field.name] &&
                    !DISPLAY_COLORS.includes(watched[field.name]!)
                      ? [watched[field.name]!]
                      : []),
                  ].map((color) => (
                    <label
                      key={color}
                      className={cn(
                        'size-7 cursor-pointer rounded-full shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)]',
                        watched[field.name] === color &&
                          'shadow-[0_0_0_2px_#fff,0_0_0_4px_var(--foreground)]',
                      )}
                      style={{ backgroundColor: color }}
                    >
                      <input
                        type="radio"
                        value={color}
                        aria-label={color}
                        className="sr-only"
                        disabled={isSubmitting}
                        {...register(field.name)}
                      />
                    </label>
                  ))}
                </div>
              ) : field.type === 'password' ? (
                <div className="relative">
                  <Input
                    id={`${formId}-${field.name}`}
                    type={shown[field.name] ? 'text' : 'password'}
                    autoComplete={
                      field.check || field.matches
                        ? 'new-password'
                        : 'current-password'
                    }
                    className={cn('pr-10', auth && 'h-10')}
                    placeholder={field.placeholder}
                    disabled={isSubmitting}
                    aria-invalid={Boolean(errors[field.name])}
                    {...register(field.name)}
                  />
                  <button
                    type="button"
                    aria-label={
                      shown[field.name] ? '비밀번호 숨기기' : '비밀번호 보기'
                    }
                    className="absolute top-0 right-0 grid h-full w-9 place-items-center text-muted-foreground hover:text-foreground"
                    onClick={() =>
                      setShown((state) => ({
                        ...state,
                        [field.name]: !state[field.name],
                      }))
                    }
                  >
                    {shown[field.name] ? (
                      <EyeOff className="size-4" />
                    ) : (
                      <Eye className="size-4" />
                    )}
                  </button>
                </div>
              ) : (
                <Input
                  id={`${formId}-${field.name}`}
                  type={field.type ?? 'text'}
                  autoComplete={field.type === 'email' ? 'email' : 'off'}
                  className={cn(auth && 'h-10')}
                  placeholder={field.placeholder}
                  maxLength={field.counter ? field.max : undefined}
                  disabled={isSubmitting}
                  aria-invalid={Boolean(errors[field.name])}
                  {...register(field.name)}
                />
              )}
              {errors[field.name] ? (
                <p role="alert" className="text-xs text-destructive">
                  {String(errors[field.name]?.message ?? '')}
                </p>
              ) : (
                field.hint && (
                  <p className="text-xs text-muted-foreground">{field.hint}</p>
                )
              )}
            </div>
          ),
        )}
      </div>
      {footer}
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {error}
        </p>
      )}
      {onCancel ? (
        // block이면 폼 너비를 1:2로 나눈다(캔버스 SCR-05-v2-password·withdraw).
        <div
          className={cn(
            'gap-2',
            block ? 'grid grid-cols-[1fr_2fr]' : 'flex justify-end',
          )}
        >
          <Button
            type="button"
            variant="outline"
            className="h-10 px-4"
            disabled={isSubmitting}
            onClick={onCancel}
          >
            취소
          </Button>
          <SheetSubmit
            destructive={destructive}
            disabled={submitDisabled}
            className={cn(
              'h-10',
              block ? 'font-semibold' : 'px-[18px] font-bold',
            )}
          >
            {isSubmitting ? '처리 중…' : submitLabel}
          </SheetSubmit>
        </div>
      ) : (
        <SheetSubmit
          destructive={destructive}
          disabled={submitDisabled}
          className={cn(block ? 'w-full' : 'self-end', auth && 'h-10')}
        >
          {isSubmitting ? '처리 중…' : submitLabel}
        </SheetSubmit>
      )}
    </form>
  )
}
