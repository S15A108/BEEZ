import { useId, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { getApiErrorMessage } from '@/api/error'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'

export interface FormField {
  name: string
  label: string
  type?: 'email' | 'password' | 'textarea' | 'select' | 'text'
  required?: boolean
  min?: number
  max?: number
  value?: string
  placeholder?: string
  matches?: string
  options?: { value: string; label: string }[]
}
interface Props {
  fields: FormField[]
  onSubmit: (values: Record<string, string>) => Promise<unknown>
  submitLabel: string
  onBusyChange?: (busy: boolean) => void
}

export function ActionForm({
  fields,
  onSubmit,
  submitLabel,
  onBusyChange,
}: Props) {
  const formId = useId()
  const [error, setError] = useState('')
  const schema = useMemo(
    () =>
      z.record(z.string(), z.string()).superRefine((values, context) => {
        for (const field of fields) {
          const value = values[field.name] ?? ''
          if (field.required && !value.trim())
            context.addIssue({
              code: 'custom',
              path: [field.name],
              message: `${field.label}을(를) 입력해 주세요.`,
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
        }
      }),
    [fields],
  )
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Record<string, string>>({
    resolver: zodResolver(schema),
    defaultValues: Object.fromEntries(
      fields.map((field) => [
        field.name,
        field.value ?? field.options?.[0]?.value ?? '',
      ]),
    ),
  })
  return (
    <form
      className="grid gap-5"
      noValidate
      onSubmit={handleSubmit(async (values) => {
        setError('')
        onBusyChange?.(true)
        try {
          await onSubmit(values)
        } catch (cause) {
          setError(getApiErrorMessage(cause))
        } finally {
          onBusyChange?.(false)
        }
      })}
    >
      {fields.map((field) => (
        <div key={field.name} className="grid gap-2">
          <Label htmlFor={`${formId}-${field.name}`}>
            {field.label}
            {field.required && (
              <span aria-hidden="true" className="text-destructive">
                {' '}
                *
              </span>
            )}
          </Label>
          {field.type === 'textarea' ? (
            <Textarea
              id={`${formId}-${field.name}`}
              rows={4}
              placeholder={field.placeholder}
              disabled={isSubmitting}
              {...register(field.name)}
            />
          ) : field.type === 'select' ? (
            <NativeSelect
              id={`${formId}-${field.name}`}
              disabled={isSubmitting}
              {...register(field.name)}
            >
              {field.options?.map((option) => (
                <NativeSelectOption key={option.value} value={option.value}>
                  {option.label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          ) : (
            <Input
              id={`${formId}-${field.name}`}
              type={field.type ?? 'text'}
              autoComplete={
                field.type === 'password'
                  ? 'current-password'
                  : field.type === 'email'
                    ? 'email'
                    : 'off'
              }
              placeholder={field.placeholder}
              disabled={isSubmitting}
              {...register(field.name)}
            />
          )}
          {errors[field.name] && (
            <p role="alert" className="text-xs text-destructive">
              {String(errors[field.name]?.message ?? '')}
            </p>
          )}
        </div>
      ))}
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {error}
        </p>
      )}
      <Button
        type="submit"
        disabled={isSubmitting}
        className="justify-self-end"
      >
        {isSubmitting ? '처리 중…' : submitLabel}
      </Button>
    </form>
  )
}
