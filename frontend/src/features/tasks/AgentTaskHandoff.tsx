import { useState } from 'react'
import { Button } from '@/components/ui/button'

export function AgentTaskHandoff({ taskId }: { taskId: string }) {
  const [feedback, setFeedback] = useState<'copied' | 'failed'>()

  async function copyInstructions() {
    const instructions = [
      `BEEZ MCP의 get_task로 작업 ID ${taskId}를 조회해 줘.`,
      '티켓의 관련 설계와 완료 기준, 작업할 저장소를 읽고 변경할 코드와 구현 순서, 검증할 테스트를 담은 구현 계획을 먼저 작성해 줘.',
      '작성한 계획을 현재 리비전과 잠금 번호를 사용해 같은 작업 ID로 upsert_task_plan에 등록해 줘.',
      '새 계획으로 validate_task_plan을 실행해 줘. RUNNING이면 get_validation으로 결과를 확인하고, 차단되면 검증 결과를 알려 줘.',
      '검증을 통과하면 현재 상태를 읽고, 시작 준비 상태면 report_task_state(START), 재검증 대기 상태면 report_task_state(RESUME)를 호출해 줘. 이미 진행 중이면 그 상태에서 구현을 이어가 줘.',
      '시작이나 재개가 task_conflict로 거절되면 작업을 다시 읽고 새 멱등 키로 현재 계획을 다시 검증해 줘.',
    ].join('\n')
    try {
      await navigator.clipboard.writeText(instructions)
      setFeedback('copied')
    } catch {
      setFeedback('failed')
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button
        size="sm"
        variant="outline"
        onClick={() => void copyInstructions()}
      >
        에이전트용 작업 지시 복사
      </Button>
      {feedback === 'copied' && (
        <p role="status" className="text-sm text-muted-foreground">
          복사했어요. 연결한 에이전트에 붙여 넣으세요.
        </p>
      )}
      {feedback === 'failed' && (
        <p role="alert" className="text-sm text-destructive">
          복사할 수 없어요. 에이전트에게 작업 ID {taskId}를 전달하세요.
        </p>
      )}
    </div>
  )
}
