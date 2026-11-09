// 팀장 시작 가이드 1단계 '팀원 초대 · 에이전트 연결'(캔버스 SCR-06-v2-workspace-preparing, -docs-ready, -baseline-ready, -baseline).
// 둘 다 끝내거나 '나중에 할게요'로 건너뛰면 완료로 센다. 건너뛴 뒤에는 남은 쪽만 다시 할 수 있게 링크를 둔다.
export function leaderSetupStep({
  invited,
  agentConnected,
  skipFlag,
  started,
}: {
  invited: boolean
  agentConnected: boolean
  /** 브라우저에 남긴 '나중에 할게요' */
  skipFlag: boolean
  started: boolean
}) {
  const finished = invited && agentConnected
  const skipped = !finished && skipFlag
  return {
    done: finished || skipped,
    skipped,
    detail: finished
      ? '팀원이 들어왔고 에이전트도 연결했어요.'
      : !skipped
        ? '둘 다 안 해도 다음 단계로 넘어갈 수 있어요.'
        : started
          ? `아까 건너뛰었어요 · ${[
              !invited && '팀원을 부르면 같이 작업 계획을 올릴 수 있어요.',
              !agentConnected && '에이전트는 설정 › 에이전트에서 연결해요.',
            ]
              .filter(Boolean)
              .join(' ')}`
          : '설정에서 언제든 할 수 있어요.',
    rows: [
      { label: '팀원 초대', action: '초대하기', done: invited, focus: 'team' },
      {
        label: '내 에이전트 연결',
        action: '연결하기',
        done: agentConnected,
        focus: 'agent',
      },
    ],
    links: [
      ...(invited
        ? []
        : [
            {
              label: started ? '팀원 초대하기' : '지금 초대하기',
              focus: 'team',
            },
          ]),
      ...(agentConnected
        ? []
        : [{ label: '에이전트 연결하기', focus: 'agent' }]),
    ],
  }
}
