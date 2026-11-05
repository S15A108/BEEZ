import { useEffect, useRef, useState } from 'react'
import type { ComponentProps, ReactNode } from 'react'
import { ArrowLeft, Check } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ProposalForm } from './ProposalForm'
import { operationLabel, operationTone } from './proposalItems'
import type { ProposalFormValues } from './proposalItems'
import { proposeChanges } from './proposeDiff'
import type { ProposedChange } from './proposeDiff'
import { Tag } from './Tag'

// 새 문서 수정 제안(캔버스 PNL-04-v2-propose-doc·changes·review·confirm, v491): 문서 등록처럼 네 단계로 나눈다.
// ① 고친 문서 등록 → ② 변경점 확인 → ③ 비비 검토 → ④ 팀장 승인. ④에서 '팀장에게 올리기'를 누를 때 처음 CHG-A01을
// 부르므로 그 전에는 팀장에게 가지 않는다. 반려 고치기·거둔 제안 다시 내기는 지금 폼(ProposalForm) 그대로 쓴다.
const STEPS = ['고친 문서 등록', '변경점 확인', '비비 검토', '팀장 승인']
const changeLabel = { UPDATE: '수정', CREATE: '추가', RETIRE: '삭제' } as const

type FormProps = Pick<
  ComponentProps<typeof ProposalForm>,
  'tasks' | 'fetchDocumentContent' | 'defaultValues'
>
interface Props extends FormProps {
  documents: {
    id: string
    label: string
    versionId?: string
    title: string
    versionNo: number
  }[]
  /** 지금 기준 버전 */
  baselineVersion?: number
  /** 지금 기준에 승인된 설계 항목의 키 → 종류 이름 */
  itemKinds: Record<string, string>
  onCancel: () => void
  /** CHG-A01로 올린다. ④ '팀장에게 올리기'에서만 부른다. */
  onSubmit: (values: ProposalFormValues) => Promise<unknown>
}

export function ProposeFlow({
  documents,
  baselineVersion,
  itemKinds,
  tasks,
  fetchDocumentContent,
  defaultValues,
  onCancel,
  onSubmit,
}: Props) {
  const [step, setStep] = useState(0)
  const [values, setValues] = useState<ProposalFormValues>()
  const [sending, setSending] = useState(false)
  // ①에서 등록할 때 비교한 기준 버전 번호. 그 뒤 승인본이 바뀌어도 비교·제출은 이 버전이므로 화면도 이 번호를 보인다.
  const [comparedNo, setComparedNo] = useState<number>()
  const document = documents.find(
    (item) => item.id === (values ?? defaultValues)?.document_id,
  )
  // 단계 중간에 승인본이 바뀌었는지. 그대로 올리면 서버가 baseline_conflict로 거절하므로 다시 비교하게 한다(PR #135 리뷰).
  const outdated =
    !!values?.base_version_id &&
    !!document?.versionId &&
    document.versionId !== values.base_version_id
  const shownNo = comparedNo ?? document?.versionNo
  const found =
    values &&
    proposeChanges(values.base_content ?? '', values.after_content ?? '')
  const task = tasks.find((item) => item.id === values?.task_id)
  const taskLabel =
    task && `${task.key} ${task.current_plan_revision.plan.title}`
  const back = [
    document ? `${document.title} v${document.versionNo}` : '변경 제안',
    '고친 문서',
    '변경점',
    '비비 검토',
  ][step]
  // 단계가 바뀌면 창 안을 맨 위(단계 표시)로 올린다. 긴 고친 글을 쓰다 넘어가면 다음 단계가 중간부터 보인다.
  const top = useRef<HTMLDivElement>(null)
  useEffect(() => {
    top.current?.scrollIntoView?.({ block: 'nearest' })
  }, [step])
  return (
    <div className="grid content-start gap-4">
      <div
        ref={top}
        className="flex scroll-mt-24 flex-wrap items-center justify-between gap-x-4 gap-y-2"
      >
        <Button
          variant="ghost"
          size="sm"
          onClick={() => (step ? setStep(step - 1) : onCancel())}
        >
          <ArrowLeft />
          {back}
        </Button>
        <Steps current={step} />
      </div>
      {step === 0 && (
        <ProposalForm
          heading="문서 수정 제안"
          description={`고친 문서를 등록하면 비비가 기준${baselineVersion ? ` v${baselineVersion}` : ''}과 비교해 바뀐 곳을 찾고 검토해요. 팀장에게는 검토를 마친 뒤 올려요.`}
          submitLabel="등록하고 변경점 찾기"
          footnote="등록해도 아직 팀장에게 가지 않아요."
          contentHint="고친 글을 쓰거나 .md 파일 한 개를 올려요. 바뀐 곳은 다음 단계에서 찾아요."
          documents={documents}
          tasks={tasks}
          fetchDocumentContent={fetchDocumentContent}
          defaultValues={values ?? defaultValues}
          onCancel={onCancel}
          onSubmit={async (form) => {
            setValues(form)
            setComparedNo(
              documents.find(
                (item) =>
                  item.id === form.document_id &&
                  item.versionId === form.base_version_id,
              )?.versionNo,
            )
            setStep(1)
          }}
        />
      )}
      {step > 0 && outdated && (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm"
        >
          <p>
            승인본이 v{document?.versionNo}로 바뀌었어요. 고친 글은 v{shownNo}과
            비교한 것이라 그대로 올리면 거절돼요.
          </p>
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              // 고친 글은 두고 기준 원문만 비워, 폼이 새 승인본을 다시 받아 비교하게 한다.
              setValues(
                (current) =>
                  current && {
                    ...current,
                    base_content: undefined,
                    base_version_id: undefined,
                  },
              )
              setComparedNo(undefined)
              setStep(0)
            }}
          >
            새 승인본과 다시 비교
          </Button>
        </div>
      )}
      {step === 1 && values && found && (
        <>
          <div>
            <h3 className="text-lg font-bold">
              바뀐 곳 {found.changes.length}개를 찾았어요
            </h3>
            <p className="mt-1 text-sm text-muted-foreground">
              {document?.title ?? '문서'} 기준 v{shownNo}과 고친 문서를 줄마다
              비교했어요. 고를 것은 없어요. 확인하고 비비 검토로 넘어가요.
            </p>
          </div>
          <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_300px]">
            <section aria-label="바뀐 곳" className="grid gap-2.5">
              <div className="flex flex-wrap items-center gap-1.5">
                {(['UPDATE', 'CREATE', 'RETIRE'] as const).map((kind) => (
                  <Tag key={kind} tone={operationTone[kind]}>
                    {changeLabel[kind]} {found.count(kind)}
                  </Tag>
                ))}
                <span className="ml-auto text-xs text-muted-foreground">
                  줄 +{found.addedLines} · -{found.removedLines}
                </span>
              </div>
              <ul className="grid gap-2">
                {found.changes.map((change, index) => (
                  <ChangeCard key={index} change={change} />
                ))}
              </ul>
            </section>
            <aside className="grid gap-3">
              <Card title="비교한 문서">
                <p className="flex items-center gap-3 text-[13px]">
                  <span>
                    <strong className="block">기준 v{shownNo}</strong>
                    <span className="text-xs text-muted-foreground">
                      승인본
                    </span>
                  </span>
                  <span aria-hidden="true">→</span>
                  <span>
                    <strong className="block">고친 문서</strong>
                    <span className="text-xs text-muted-foreground">
                      방금 등록
                    </span>
                  </span>
                </p>
                <p className="text-xs text-muted-foreground">
                  {document?.title} · 바뀌지 않은 줄은 빼고 보여 줘요.
                </p>
                <WholeText text={values.after_content ?? ''} />
              </Card>
              <Card title="바뀐 줄의 설계 항목">
                {/* API 없음: 바뀐 곳에서 이어진 설계 항목을 찾아 CHG-A01 items에 채우는 기능(수정안 요청). 지금은 바뀐 줄에
                    적힌 키를 지금 기준의 승인 항목과 맞춰 보여 주기만 하고 제안에는 넣지 않는다. */}
                <ItemKeys changes={found.changes} itemKinds={itemKinds} />
              </Card>
            </aside>
          </div>
          <Footer
            note="다음 단계에서 비비가 영향받는 작업과 빠진 부분을 찾아요."
            back={['다시 고치기', () => setStep(0)]}
          >
            <Button onClick={() => setStep(2)}>비비 검토 받기</Button>
          </Footer>
        </>
      )}
      {step === 2 && (
        <>
          <div>
            <h3 className="text-lg font-bold">
              올리기 전 비비 검토는 준비 중이에요
            </h3>
            <p className="mt-1 text-sm text-muted-foreground">
              아직 팀장에게 가지 않았어요. 확인한 내용 그대로 다음 단계로 갈 수
              있어요.
            </p>
          </div>
          {/* API 없음(캔버스 수정안 요청): 올리기 전 영향 검토(CHG-A05·A06은 올린 제안만), 문서 변경의 AI 영향 후보,
              빠진 부분·충돌 검토와 넘긴 의견 저장. API가 생기면 이 단계에 검토 결과와 넘기기·반영하기(CHG-A07)를 둔다. */}
          <div className="grid gap-3 lg:grid-cols-2">
            <Card title="영향받는 작업·검증">
              <p className="text-[13px]">
                {taskLabel ? `연결한 작업: ${taskLabel}` : '연결한 작업 없음'}
              </p>
              <p className="text-xs text-muted-foreground">
                바뀐 곳에서 영향받는 작업과 AI 영향 후보를 찾는 검토는 아직
                없어요.
              </p>
            </Card>
            <Card title="빠진 부분·충돌">
              <p className="text-xs text-muted-foreground">
                고친 글에서 빠진 부분과 다른 문서와의 충돌을 찾는 검토는 아직
                없어요.
              </p>
            </Card>
          </div>
          <Footer
            note="검토가 준비되면 여기서 비비 의견을 넘기거나 반영해요."
            back={['다시 고치기', () => setStep(0)]}
          >
            <Button onClick={() => setStep(3)}>다음</Button>
          </Footer>
        </>
      )}
      {step === 3 && values && found && (
        <>
          <div>
            <h3 className="text-lg font-bold">올리기 전에 확인해요</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              올리면 검토 대기로 팀장에게 가요. 올린 뒤에는 고칠 수 없고
              거두기만 할 수 있어요.
            </p>
          </div>
          <div className="grid items-start gap-5 lg:grid-cols-2">
            <dl className="grid gap-3 text-sm">
              <Field label="제목">{values.title}</Field>
              <Field label="바꾸려는 이유">{values.reason}</Field>
              <Field label="고친 문서">
                {document?.title ?? '문서'} · 기준 v{shownNo}에서 고친 글
                <WholeText text={values.after_content ?? ''} />
              </Field>
              {taskLabel && <Field label="관련 작업">{taskLabel}</Field>}
            </dl>
            <section aria-label="바뀌는 곳" className="grid gap-1">
              <h4 className="text-sm font-bold">
                바뀌는 곳 {found.changes.length}
              </h4>
              <ul>
                {found.changes.map((change, index) => (
                  <li
                    key={index}
                    className="flex items-center gap-2 border-b py-2 text-[13px] last:border-b-0"
                  >
                    <Tag tone={operationTone[change.kind]}>
                      {changeLabel[change.kind]}
                    </Tag>
                    {change.key && <strong>{change.key}</strong>}
                    <span className="truncate text-muted-foreground">
                      {(change.after ?? change.before)!
                        .replace(change.key ?? '', '')
                        .trim()}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          </div>
          <Footer
            note="올리면 팀장에게 알림이 가요."
            back={['검토로 돌아가기', () => setStep(2)]}
          >
            <Button
              disabled={sending || outdated}
              onClick={() => {
                setSending(true)
                void onSubmit(values)
                  .catch(() => undefined)
                  .finally(() => setSending(false))
              }}
            >
              {sending ? '올리는 중…' : '팀장에게 올리기'}
            </Button>
          </Footer>
        </>
      )}
    </div>
  )
}

function Steps({ current }: { current: number }) {
  return (
    <ol
      aria-label="문서 수정 제안 단계"
      className="flex flex-wrap items-center gap-x-1 gap-y-2"
    >
      {STEPS.map((label, index) => (
        <li
          key={label}
          aria-current={index === current ? 'step' : undefined}
          className="flex items-center gap-2"
        >
          <span
            className={cn(
              'grid size-6 place-items-center rounded-full text-xs font-bold',
              index < current && 'bg-primary text-[#322710]',
              index === current &&
                'border-2 border-primary bg-white text-[#322710] shadow-[0_0_0_3px_#fdf3d3]',
              index > current && 'border border-[#d4d0c4] text-[#8a877e]',
            )}
          >
            {index < current ? (
              <Check aria-hidden="true" className="size-3.5" />
            ) : (
              index + 1
            )}
            {index < current && <span className="sr-only">완료</span>}
          </span>
          <span
            className={cn(
              'text-[13px]',
              index === current ? 'font-bold' : 'text-[#8a877e]',
              index < current && 'text-foreground',
            )}
          >
            {label}
          </span>
          {index < STEPS.length - 1 && (
            <span
              aria-hidden="true"
              className={cn(
                'mx-1 h-0.5 w-7 rounded-full',
                index < current ? 'bg-primary' : 'bg-[#e5e2d9]',
              )}
            />
          )}
        </li>
      ))}
    </ol>
  )
}

function ChangeCard({ change }: { change: ProposedChange }) {
  return (
    <li className="grid gap-2 rounded-xl border px-3 py-2.5 text-[13px]">
      <p className="flex items-center gap-2">
        <Tag tone={operationTone[change.kind]}>{changeLabel[change.kind]}</Tag>
        {change.key && <strong>{change.key}</strong>}
        {change.section && (
          <span className="text-xs text-muted-foreground">
            {change.section}
          </span>
        )}
      </p>
      {change.before && (
        <p className="rounded-md bg-[#fdecec] px-2.5 py-1 text-[#b42318]">
          <span aria-label="뺀 줄">- </span>
          {change.before}
        </p>
      )}
      {change.after && (
        <p className="rounded-md bg-[#e8f3ec] px-2.5 py-1">
          <span aria-label="더한 줄">+ </span>
          {change.after}
        </p>
      )}
    </li>
  )
}

function ItemKeys({
  changes,
  itemKinds,
}: {
  changes: ProposedChange[]
  itemKinds: Record<string, string>
}) {
  const keys = [...new Set(changes.flatMap((change) => change.key ?? []))]
  return (
    <>
      <p className="text-xs text-muted-foreground">
        바뀐 줄에 적힌 항목 키예요. 이어진 설계 항목을 찾아 제안에 넣는 기능은
        아직 없어 보여 주기만 해요.
      </p>
      {keys.length ? (
        <ul className="grid">
          {keys.map((key) => (
            <li
              key={key}
              className="flex items-center gap-2 border-b py-1.5 text-[13px] last:border-b-0"
            >
              <strong>{key}</strong>
              <span className="text-xs text-muted-foreground">
                {itemKinds[key]}
              </span>
              <span className="ml-auto text-xs text-muted-foreground">
                {itemKinds[key] ? operationLabel.UPDATE : operationLabel.CREATE}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-[13px]">바뀐 줄에 항목 키가 없어요.</p>
      )}
    </>
  )
}

function WholeText({ text }: { text: string }) {
  return (
    <details className="text-[13px]">
      <summary className="cursor-pointer font-semibold text-[#8a6a00]">
        원문 전체 보기
      </summary>
      <pre className="mt-2 max-h-64 overflow-auto rounded-lg bg-muted/40 p-2.5 font-mono text-xs whitespace-pre-wrap">
        {text}
      </pre>
    </details>
  )
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section
      aria-label={title}
      className="grid gap-2 rounded-[14px] border px-4 py-3.5"
    >
      <h4 className="text-[15px] font-bold">{title}</h4>
      {children}
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap">{children}</dd>
    </div>
  )
}

function Footer({
  note,
  back,
  children,
}: {
  note: string
  back: [string, () => void]
  children: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-4">
      <p className="mr-auto text-xs text-muted-foreground">{note}</p>
      <Button variant="outline" onClick={back[1]}>
        {back[0]}
      </Button>
      {children}
    </div>
  )
}
