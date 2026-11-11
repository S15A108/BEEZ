import { useEffect } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { cn } from '@/lib/utils'

// 에이전트 연결 도움말(AGT-04). 원본은 docs/guides/agent-connection.md(S15P31A108-337)이고, 문구를 바꿀 때는 그 문서를 먼저 고친다.
// 앱에서는 해요체로 바꾸고 내부 메모(요구사항·티켓 번호, 변경 이력)와 dev 서버 주소를 뺐다(docs/decisions.md D43).

const Code = ({ children }: { children: ReactNode }) => (
  <code className="rounded-[5px] bg-muted px-1.5 py-px font-mono text-[13px]">
    {children}
  </code>
)

const Block = ({ children }: { children: string }) => (
  <pre className="rounded-[10px] bg-foreground px-4 py-3 font-mono text-[13px] leading-[1.6] break-all whitespace-pre-wrap text-[#f5f1e3] select-text">
    {children}
  </pre>
)

function Section({
  id,
  title,
  children,
}: {
  id: string
  title: string
  children: ReactNode
}) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="scroll-mt-6">
      <h3
        id={`${id}-title`}
        className="mb-3 border-b pb-2 text-xl leading-[1.4] font-bold"
      >
        {title}
      </h3>
      <div className="grid gap-3">{children}</div>
    </section>
  )
}

function Table({
  head,
  rows,
  className,
}: {
  head: string[]
  rows: ReactNode[][]
  className?: string
}) {
  return (
    <table
      className={cn('w-full border-collapse text-sm leading-[1.6]', className)}
    >
      <thead>
        <tr>
          {head.map((cell) => (
            <th
              key={cell}
              scope="col"
              className="border-b border-[#d1d5db] bg-muted px-3 py-2 text-left font-semibold"
            >
              {cell}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index}>
            {row.map((cell, column) => (
              <td key={column} className="border-b px-3 py-2 align-top">
                {cell}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

const reconnect = (
  <>
    <Code>/mcp</Code> → <Code>beez</Code> → Reconnect
  </>
)

export default function AgentHelpPage() {
  const { hash } = useLocation()
  // 라우트가 lazy라 새 탭 첫 로드에서 브라우저가 해시로 스크롤할 때는 절이 아직 없다. 그린 뒤 직접 내려간다(PR #230 리뷰).
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView()
  }, [hash])
  return (
    <main className="mx-auto grid max-w-[760px] gap-9 px-6 pt-10 pb-20 text-[15px] leading-[1.7] break-keep select-text">
      <header className="grid gap-2">
        <h2 className="text-[26px] leading-[1.35] font-bold">
          에이전트(Claude Code) 연결 도움말
        </h2>
        <p className="text-muted-foreground">
          로컬 Claude Code를 BEEZ에 연결해 승인된 설계 조회, 작업 계획, 검증,
          상태 보고를 MCP로 써요. 프로젝트 멤버 누구나 연결할 수 있고, 토큰은
          나와 이 프로젝트에서만 써요.
        </p>
      </header>

      <Section id="rules" title="먼저 알아 둘 규칙 두 가지">
        <ol className="grid list-decimal gap-2 pl-[22px]">
          <li>
            <strong>한 사람은 프로젝트마다 연결 하나만 살아 있어요.</strong>{' '}
            토큰이 달라도(다른 PC, 다른 터미널) 같은 계정이면{' '}
            <strong>나중 연결이 이겨요.</strong> 먼저 붙어 있던 쪽은 다음
            호출에서 409 <Code>agent_session_replaced</Code>를 받고 끊겨요. 다른
            팀원의 연결은 건드리지 않아요.
          </li>
          <li>
            <strong>토큰 원문은 발급할 때 한 번만 보여요.</strong> 놓쳤으면
            폐기하고 새로 발급해요. 한 사람이 한 프로젝트에 가질 수 있는 폐기 안
            된 토큰은 5개예요.
          </li>
        </ol>
      </Section>

      <Section id="issue" title="1. 토큰 발급 (웹)">
        <ol className="grid list-decimal gap-1 pl-[22px]">
          <li>웹에 로그인하고 연결할 프로젝트로 들어가요.</li>
          <li>
            <strong>설정 → 내 에이전트 관리</strong>로 가요. 프로젝트 주소 끝에{' '}
            <Code>?settings=agent</Code>를 붙여도 열려요.
          </li>
          <li>
            <strong>1 토큰 발급</strong>에서 토큰 이름(기본{' '}
            <Code>내 Claude Code</Code>, PC를 구분하려면{' '}
            <Code>회사 노트북</Code> 등)을 넣고 <strong>토큰 발급</strong>을
            눌러요.
          </li>
          <li>
            바로 아래 <strong>2 Claude Code에 BEEZ 추가</strong>에 토큰이 들어간
            명령이 나와요. 화면을 닫기 전에 <strong>복사</strong>해요.
          </li>
        </ol>
      </Section>

      <Section id="add" title="2. Claude Code에 BEEZ 추가 (터미널)">
        <p>
          작업할 저장소 폴더에서 복사한 명령을 실행해요. Git Bash와 PowerShell
          모두 같아요. 설정에서 복사한 명령에는 이 서버의 MCP 주소와 토큰이 들어
          있어요.
        </p>
        <Block>
          {
            'claude mcp add --transport http beez https://<BEEZ 서버>/mcp --header "Authorization: Bearer <토큰>"'
          }
        </Block>
        <ul className="grid list-disc gap-1 pl-[22px]">
          <li>
            이 명령은 <strong>등록만 하고 끝나요.</strong> 대화 창이 뜨지 않는
            게 정상이에요.
          </li>
          <li>
            등록은 <strong>명령을 실행한 폴더</strong>에 걸려요(기본 범위
            local). 다음 단계의 <Code>claude</Code>도 같은 폴더에서 켜요.
          </li>
          <li>
            이미 <Code>beez</Code>라는 이름이 있으면{' '}
            <Code>claude mcp remove beez</Code>로 지우고 다시 넣거나, 이름을
            바꿔(<Code>beez-dev</Code>) 넣어요.
          </li>
        </ul>
      </Section>

      <Section id="claude-md" title="3. CLAUDE.md에 작업 절차 붙여넣기">
        <p>
          설정의 <strong>3 CLAUDE.md에 작업 절차 붙여넣기</strong>에서{' '}
          <strong>CLAUDE.md 내용 복사</strong>를 눌러 저장소의{' '}
          <Code>CLAUDE.md</Code>에 붙여요. 계획 등록 → 검증 → 시작 → 변경 확인 →
          완료 보고 순서를 에이전트가 따르게 하는 안내예요(연결할 때 서버도 같은
          글을 보내요).
        </p>
      </Section>

      <Section id="check" title="4. Claude Code 켜고 연결 확인">
        <p>같은 폴더에서 Claude Code를 켜요.</p>
        <Block>claude</Block>
        <p>대화 창에서 아래 순서로 확인해요.</p>
        <ol className="grid list-decimal gap-2 pl-[22px]">
          <li>
            <Code>/mcp</Code>를 입력해 <Code>beez</Code>가 connected인지 봐요.
          </li>
          <li className="space-y-2">
            이렇게 부탁해요.
            <Block>
              beez의 check_connection을 호출하고 결과를 그대로 보여 줘
            </Block>
            <p>
              <Code>"connected": true</Code>와 내 <Code>project_id</Code>,{' '}
              <Code>account_id</Code>가 나오면 돼요.
            </p>
          </li>
          <li>
            웹 설정 왼쪽 메뉴 <strong>내 에이전트 관리</strong> 옆 표시가{' '}
            <strong>{agentStatusLabels.connected}</strong>이면 돼요(최근 2시간
            안에 통신한 살아 있는 연결이 있다는 뜻이에요).
          </li>
        </ol>
        <p>
          설정 → 내 에이전트 관리 위쪽 상태 줄의 뜻은 아래와 같아요. 상태 줄에
          "연결 상태 확인은 준비 중이에요"가 나오면 서버가 아직 연결 현황을 주지
          않는 것이니 왼쪽 메뉴 표시로 봐요.
        </p>
        <Table
          head={['표시', '뜻']}
          className="[&_td:first-child]:whitespace-nowrap"
          rows={[
            [
              agentStatusLabels.connected,
              '최근 2시간 안에 통신한 살아 있는 연결이 있어요. 맡은 작업 없이 쉬는 중이어도 연결됨이에요.',
            ],
            [
              agentStatusLabels.recheck,
              '연결은 살아 있지만 진행 중인 작업을 맡은 채 30분 넘게 통신이 없어요. 작업이 멈췄는지 Claude Code 창을 확인해요.',
            ],
            [
              agentStatusLabels.failed,
              <>
                연결 확인을 눌렀는데 살아 있는 연결이 없어요. 아래{' '}
                <a href="#troubleshooting" className="underline">
                  문제 해결
                </a>
                을 봐요.
              </>,
            ],
            [
              agentStatusLabels.disconnected,
              '2시간 넘게 통신이 없거나 세션이 끝났어요. Claude Code를 다시 켜고 연결 확인을 눌러요.',
            ],
            [agentStatusLabels.none, '아직 연결한 적이 없어요.'],
          ]}
        />
      </Section>

      <Section id="disconnect" title="5. 연결 해제">
        <ul className="grid list-disc gap-2 pl-[22px]">
          <li>
            <strong>잠깐 멈출 때</strong>: Claude Code를 끄면 돼요. 2시간이
            지나면 웹에서 연결로 보지 않아요.
          </li>
          <li className="space-y-2">
            <p>
              <strong>토큰을 더 쓰지 않을 때</strong>: 설정 → 내 에이전트 관리 →{' '}
              <strong>내 토큰</strong>에서 그 토큰을 <strong>폐기</strong>해요.
              열려 있던 연결도 다음 호출부터 거부돼요. 로컬 등록도 지워요.
            </p>
            <Block>claude mcp remove beez</Block>
          </li>
        </ul>
      </Section>

      <Section id="troubleshooting" title="문제 해결">
        <Table
          head={['증상', '원인', '할 일']}
          rows={[
            [
              <>
                브라우저로 MCP 주소를 열었더니{' '}
                <Code>{'{"error":{"code":"unauthenticated",…}}'}</Code>
              </>,
              '브라우저는 토큰을 보내지 않아요.',
              '정상이에요. MCP 주소는 Claude Code가 토큰과 함께 불러요.',
            ],
            [
              <>
                Claude Code가 <Code>rejected the Authorization header</Code>{' '}
                또는 401 <Code>unauthenticated</Code>
              </>,
              '토큰이 비었거나 서버에 없는 값이에요(복사 실수, 다른 서버에서 발급한 토큰).',
              <>
                발급한 서버와 MCP 주소가 같은지, <Code>Bearer</Code>와 토큰
                사이에 공백이 한 칸인지 봐요. 안 되면 새로 발급해 다시 등록해요.
              </>,
            ],
            [
              <>
                401 <Code>token_revoked</Code> (Claude Code에는 위와 같은 문구로
                보일 수 있어요)
              </>,
              '그 토큰을 폐기했어요.',
              '새 토큰을 발급해 다시 등록해요.',
            ],
            [
              <>
                409 <Code>agent_session_replaced</Code>
              </>,
              <>
                같은 계정이 이 프로젝트에 <strong>새로 연결</strong>했어요. 다른
                PC나 다른 터미널의 <Code>claude</Code>,{' '}
                <strong>
                  작업 중 다른 터미널에서 친 <Code>claude mcp list</Code>
                </strong>
                (상태 확인도 새 연결이에요)가 모두 해당해요.
              </>,
              <>
                쓸 창 하나만 남기고 그 창에서 {reconnect}을 눌러요. 작업 중에는
                다른 터미널에서 <Code>claude mcp list</Code>를 치지 않아요.
              </>,
            ],
            [
              <>
                404 <Code>not_found</Code> (MCP 세션이 없어요)
              </>,
              '연결이 닫혔거나 서버가 세션을 잊었어요(마지막 호출 뒤 1~2시간 조용함, 서버 재배포).',
              <>{reconnect}을 누르거나 Claude Code를 다시 켜요.</>,
            ],
            [
              <>
                <Code>claude mcp list</Code>에 <Code>beez</Code>가 없어요
              </>,
              '다른 폴더에서 등록했어요.',
              <>
                등록한 폴더에서 <Code>claude</Code>를 켜거나, 이 폴더에서 다시
                등록해요.
              </>,
            ],
            [
              '토큰 발급이 안 돼요(한도)',
              '폐기 안 된 토큰이 5개예요.',
              '내 토큰에서 안 쓰는 토큰을 폐기해요.',
            ],
          ]}
        />
      </Section>
    </main>
  )
}
