import type { UsageBucket } from '@/api/contracts/common'
import { cn } from '@/lib/utils'
import { callsText } from './labels'
import { axisMax, monthDayText, rangeText, weekdayText } from './period'

// 날짜별 AI 호출 막대(MON-A02 by_day). 라이브러리 없이 CSS로 그린다. 값은 화면 밖 표로도 읽을 수 있다.
export function DailyCallsChart({
  rows,
  currency,
}: {
  rows: UsageBucket[]
  currency?: string
}) {
  const total = rows.reduce((sum, row) => sum + row.call_count, 0)
  if (!total)
    return (
      <p className="py-8 text-center text-xs text-muted-foreground">
        이 기간에 기록된 AI 호출이 없어요.
      </p>
    )
  const most = Math.max(...rows.map((row) => row.call_count))
  const top = axisMax(most)
  const ticks = top % 2 ? [top, 0] : [top, top / 2, 0]
  const last = rows.length - 1
  const peak = rows.find((row) => row.call_count === most)!
  // 첫날부터 일주일마다, 그리고 마지막 날. 마지막 날과 붙는 눈금은 뺀다.
  const stride = Math.max(7, Math.ceil(rows.length / 5))
  const showDay = (index: number) =>
    index === last || (index % stride === 0 && last - index >= stride / 2)
  const position = (value: number) => `${(1 - value / top) * 100}%`
  const dayLabel = (key: string) => {
    const [, month, date] = key.split('-').map(Number)
    return `${month}/${date}`
  }
  return (
    <figure className="m-0">
      <div aria-hidden="true" className="flex gap-1.5">
        <div className="relative h-36 w-6 shrink-0 text-right text-[11px] text-muted-foreground tabular-nums">
          {ticks.map((tick) => (
            <span
              key={tick}
              className="absolute right-0 -translate-y-1/2"
              style={{ top: position(tick) }}
            >
              {tick.toLocaleString('ko-KR')}
            </span>
          ))}
        </div>
        <div className="min-w-0 flex-1">
          <div className="relative h-36 border-b border-[#c3c2b7]">
            {ticks.slice(0, -1).map((tick) => (
              <span
                key={tick}
                className="absolute inset-x-0 border-t"
                style={{ top: position(tick) }}
              />
            ))}
            {/* ponytail: 두 달을 넘기면 막대가 2px보다 좁아져 틈을 없앤다. 긴 기간은 값을 툴팁과 표로 본다. */}
            <ol
              className={cn(
                'absolute inset-0 flex items-end',
                rows.length <= 62 && 'gap-0.5',
              )}
            >
              {rows.map((row, index) => (
                <li
                  key={row.key}
                  className="group relative flex h-full min-w-0 flex-1 items-end justify-center hover:bg-muted/60"
                >
                  {row.call_count > 0 && (
                    <span
                      className="block w-full max-w-6 min-w-px rounded-t-[4px] bg-[#2a78d6]"
                      style={{
                        height: `${(row.call_count / top) * 100}%`,
                        minHeight: 2,
                      }}
                    />
                  )}
                  <span
                    className={cn(
                      'pointer-events-none absolute z-10 mb-1 hidden rounded-md border bg-popover px-2 py-1 text-[11px] whitespace-nowrap text-popover-foreground shadow-sm group-hover:block',
                      index < rows.length / 4
                        ? 'left-0'
                        : index > (rows.length * 3) / 4
                          ? 'right-0'
                          : 'left-1/2 -translate-x-1/2',
                    )}
                    style={{ bottom: `${(row.call_count / top) * 100}%` }}
                  >
                    {`${monthDayText(row.key)}(${weekdayText(row.key)}) · ${
                      row.call_count ? callsText(row, currency) : '호출 없음'
                    }`}
                  </span>
                </li>
              ))}
            </ol>
          </div>
          <div className="relative mt-1 h-4 text-[11px] text-muted-foreground tabular-nums">
            {rows.map(
              (row, index) =>
                showDay(index) && (
                  <span
                    key={row.key}
                    className="absolute -translate-x-1/2"
                    style={{ left: `${((index + 0.5) / rows.length) * 100}%` }}
                  >
                    {dayLabel(row.key)}
                  </span>
                ),
            )}
          </div>
        </div>
      </div>
      <table className="sr-only">
        <caption>
          {`${rangeText(rows[0].key, rows[last].key)} 날짜별 AI 호출. 합계 ${total.toLocaleString('ko-KR')}회, 가장 많은 날은 ${monthDayText(peak.key)} ${most.toLocaleString('ko-KR')}회`}
        </caption>
        <thead>
          <tr>
            <th scope="col">날짜</th>
            <th scope="col">호출</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <th scope="row">{monthDayText(row.key)}</th>
              <td>{callsText(row, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}
