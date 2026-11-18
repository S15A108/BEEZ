export const OFFICE_INTENTS = [
  'come',
  'follow',
  'stop',
  'resume',
  'activity',
  'work_status',
  'clarify',
  'none',
] as const
export type OfficeIntent = (typeof OFFICE_INTENTS)[number]
export type OfficeMovementIntent = Extract<
  OfficeIntent,
  'come' | 'follow' | 'stop' | 'resume'
>
export function isOfficeMovementIntent(
  intent: OfficeIntent,
): intent is OfficeMovementIntent {
  return (
    intent === 'come' ||
    intent === 'follow' ||
    intent === 'stop' ||
    intent === 'resume'
  )
}
export type OfficeBehavior = 'autonomous' | 'visiting' | 'following' | 'waiting'
export interface OfficeIntentContext {
  name?: string
  behavior?: OfficeBehavior
  activity?: string
  history?: { role: 'user' | 'assistant'; content: string }[]
}
export interface OfficeIntentResult {
  intent: OfficeIntent
  source: 'rules' | 'model' | 'fallback'
}

export function officeUtterance(message: string, name?: string): string {
  let text = message.trim().replace(/[\s,.!?~。！？…]+/gu, '')
  const who = name?.replace(/\s+/gu, '')
  if (who && text.startsWith(who))
    text = text.slice(who.length).replace(/^[야아]/u, '')
  return text.replace(/^(?:혹시|저기|있잖아|있잖아요)/u, '')
}

/** Current work records answer progress questions; policy and implementation requests stay separate. */
export function isOfficeWorkStatusQuestion(message: string): boolean {
  const text = officeUtterance(message)
  if (
    /정책|설계|결정|근거|명세|규격|규칙|검증기준|기능|코드(?:보여|설명|작성|수정)|만들|추가해|구현해|개발해|해석|무슨뜻|라고|라는|문장|명령어/iu.test(
      text,
    )
  )
    return false
  return (
    /(?:작업|프로젝트|업무|개발|우리팀|전체).*(?:현황|진행|진척|진도|상태|어디까지|얼마나|남았|남은|끝났|완료됐)/u.test(
      text,
    ) ||
    /^(?:지금|현재|전체|우리팀)*(?:현황|진행상황|진행상태|진척|진도)/u.test(
      text,
    ) ||
    /^(?:지금|현재)?(?:진행중인|남은|완료된)작업/u.test(text)
  )
}

/** Reliable direct intents stay immediate; unfamiliar phrasing can use contextual model parsing. */
export function officeIntentRule(
  message: string,
  context: OfficeIntentContext = {},
): { intent: OfficeIntent; allowModel: boolean } {
  const text = officeUtterance(message, context.name)
  const result = (intent: OfficeIntent, allowModel = false) => ({
    intent,
    allowModel,
  })
  if (isOfficeWorkStatusQuestion(message)) return result('work_status')
  // Talking about a command or asking for code is not an instruction to move.
  if (
    /라고|라는|뜻|문장|명령어|기능|구현|개발|코드|파일|테스트|검토|리뷰|설계|결정|정책|근거|규칙|명세|규격|검증기준|토큰|모델|오류|버그|배포|기획|요구사항|API|commit|만들어/iu.test(
      text,
    )
  )
    return result('none')
  if (
    /^(?:지금|지금은|현재|너|넌)*(?:뭐|무엇|무슨일|어떤일|무슨작업)(?:해|하니|해요|하나요|하고있어|하고있니|하고있어요|하고있나요|하는중이야|하는중이에요|하는지알려줘|중이야|중이에요)(?:요)?$/u.test(
      text,
    ) ||
    /^(?:지금)?어디(?:가|가니|가고있어|가는중이야|있어|있니|있나요)(?:요)?$/u.test(
      text,
    )
  )
    return result('activity')
  if (
    /^(?:이제|이젠|이제는|더는|더이상|그만|잠깐)*(?:따라오지마|따라오지말아줘|따라오지마세요|오지마|오지마세요|멈춰|멈춰줘|멈춰주세요|멈춰봐|그만따라와|그만따라와줘|그만따라오세요|그만와|여기있어|여기있어줘|여기서기다려|여기서기다려줘|기다려|기다려줘|기다려주세요|스톱)$/u.test(
      text,
    ) ||
    /^(?:stopfollowing(?:me)?|stop|stayhere|waithere)$/iu.test(text)
  )
    return result('stop')
  if (
    /^(?:이제|이젠|그럼)?(?:됐어|됐어요|그만|그만해|거기까지|여기까지)$/u.test(
      text,
    )
  )
    return result(
      context.behavior && context.behavior !== 'autonomous' ? 'stop' : 'none',
    )
  if (
    /^(?:계속|앞으로|잠깐|이제|그럼)*(?:나를?|저를?|내뒤를|제뒤를)?(?:따라와|따라와봐|따라와줘|따라와주세요|따라와줄래|따라와줄래요|따라오세요|따라올래|따라올래요|따라다녀|따라다녀줘|따라다녀주세요|쫓아와|쫓아와봐|쫓아와줘|같이가자|같이가요|같이걸어줘|같이와)(?:요)?$/u.test(
      text,
    ) ||
    /^(?:please)?followme(?:please)?$/iu.test(text)
  )
    return result('follow')
  if (
    /^(?:잠깐|한번|좀|이제)*(?:이리(?:로)?|여기(?:로)?|이쪽(?:으로)?|내(?:쪽|앞|옆)(?:으로)?|나한테|저한테|나에게|저에게|제(?:쪽|앞|옆)(?:으로)?)(?:좀)?(?:온|와(?:줘|주세요|줄래|줄래요|줄수있어|줄수있나요|봐|봐줘|요)?|오(?:세요|너라|렴|실래요|실래|거라))$/u.test(
      text,
    ) ||
    /^(?:please)?comehere(?:please)?$/iu.test(text)
  )
    return result('come')
  if (
    /^(?:이제|그럼)?(?:돌아가|돌아가줘|자리로돌아가|자리로돌아가줘|원래자리로돌아가|할일해|하던일해|하던일계속해|다시일해)(?:요)?$/u.test(
      text,
    )
  )
    return result('resume')
  if (
    /(?:오지|따라오지|따라가지)(?:는)?(?:마|말)|안따라|따라오면안|오면안|오라는|오라고|따라오라고/u.test(
      text,
    )
  )
    return result('none')
  if (
    context.behavior === 'following' &&
    /^(?:계속|계속해|그대로|계속해줘)$/u.test(text)
  )
    return result('follow')
  return result('none', true)
}
