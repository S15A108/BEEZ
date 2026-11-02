import type { ReactNode } from 'react'
import { Check } from 'lucide-react'
import brickArt from '@/assets/buildings/brick.png'
import glassArt from '@/assets/buildings/glass.png'
import modernArt from '@/assets/buildings/modern.png'
import towerArt from '@/assets/buildings/tower.png'
import woodArt from '@/assets/buildings/wood.png'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { cn } from '@/lib/utils'

// 이름·색·설명은 캔버스 SCR-04-v2-create-project 보드 그대로다. 그림은 도시와 같은 Kenney 건물(D22, src/three/buildingModels.ts)에
// 같은 색을 곱해 렌더한 것이라, 고른 디자인이 도시·산책 화면에서 같은 모델로 보인다.
const buildingDesigns: {
  value: BuildingDesign
  label: string
  color: string
  description: string
  art: string
}[] = [
  {
    value: 'glass',
    label: '유리 오피스',
    color: '#6f9db4',
    description: '유리 외벽 · 평지붕 · 옥상 설비',
    art: glassArt,
  },
  {
    value: 'brick',
    label: '붉은 벽돌',
    color: '#c4704f',
    description: '벽돌 외벽 · 흰 창틀 · 굴뚝',
    art: brickArt,
  },
  {
    value: 'modern',
    label: '모던 화이트',
    color: '#ebe7dc',
    description: '흰 외벽 · 가로 띠창 · 옥상 정원',
    art: modernArt,
  },
  {
    value: 'tower',
    label: '다크 타워',
    color: '#45454e',
    description: '짙은 외벽 · 높고 좁은 몸체 · 안테나',
    art: towerArt,
  },
  {
    value: 'wood',
    label: '목조 스튜디오',
    color: '#b98a52',
    description: '나무 외벽 · 박공지붕 · 큰 창',
    art: woodArt,
  },
]

const designOf = (value: BuildingDesign) =>
  buildingDesigns.find((item) => item.value === value) ?? buildingDesigns[2]

// 건물 그림(나의 도시 카드·프로젝트 만들기). 바닥에 붙여 세운다.
export function BuildingArt({
  design,
  className,
}: {
  design: BuildingDesign
  className?: string
}) {
  return (
    <img
      src={designOf(design).art}
      alt=""
      className={cn('block w-auto max-w-full', className)}
    />
  )
}

function DesignDot({ design, size }: { design: BuildingDesign; size: number }) {
  return (
    <span
      aria-hidden="true"
      className="shrink-0 rounded-full"
      style={{
        width: size,
        height: size,
        background: designOf(design).color,
        // 모던 화이트는 흰 바탕에서 보이게 테두리를 둔다.
        boxShadow: design === 'modern' ? 'inset 0 0 0 1px #c9c3b3' : undefined,
      }}
    />
  )
}

// 프로젝트 만들기(SCR-04-v2-create-project)와 설정 › 프로젝트 정보(SCR-06-v2-settings-project 11-02)가 같이 쓰는
// 건물 디자인 고르기: 왼쪽 큰 미리 보기와 이름·특징, 오른쪽 그림·이름·특징 카드에 노란 테두리와 체크.
// small: 설정 창 높이에 맞춘 작은 미리 보기. note: 미리 보기 아래 안내(설정의 '지금 부지').
export function BuildingDesignPicker({
  value,
  onChange,
  small,
  note,
}: {
  value: BuildingDesign
  onChange: (value: BuildingDesign) => void
  small?: boolean
  note?: ReactNode
}) {
  const label = designOf(value).label
  return (
    <div
      className={cn(
        'grid sm:grid-cols-[240px_minmax(0,1fr)]',
        small ? 'gap-4 sm:grid-cols-[196px_minmax(0,1fr)]' : 'gap-6',
      )}
    >
      <figure
        aria-label={`선택한 건물 미리 보기: ${label}`}
        className={cn(
          'm-0 grid content-center justify-items-center gap-2.5 rounded-2xl bg-[#f4f2ec]',
          small ? 'px-3 py-4' : 'px-4 py-5',
        )}
      >
        <BuildingArt
          design={value}
          className={small ? 'h-[136px]' : 'h-[196px]'}
        />
        <figcaption className="grid justify-items-center gap-0.5 text-center">
          <span className="inline-flex items-center gap-1.5 text-sm font-bold">
            <DesignDot design={value} size={9} />
            {label}
          </span>
          <span className="text-xs leading-[17px] text-[#6b6658]">
            {designOf(value).description}
          </span>
        </figcaption>
        {note}
      </figure>
      <fieldset className="grid content-start gap-2.5">
        <legend
          className={cn(
            'mb-2.5 font-medium',
            small ? 'text-[13px]' : 'text-sm',
          )}
        >
          건물 디자인
        </legend>
        <div className="grid grid-cols-3 gap-2.5">
          {buildingDesigns.map((item) => {
            const checked = value === item.value
            return (
              <label
                key={item.value}
                className={cn(
                  'relative grid cursor-pointer gap-1.5 rounded-[14px] border border-border bg-white px-2 pt-2 pb-2.5 has-focus-visible:ring-2 has-focus-visible:ring-ring',
                  checked &&
                    'border-2 border-[#c99e1e] bg-[#fffaeb] px-[7px] pt-[7px] pb-[9px]',
                )}
              >
                <input
                  type="radio"
                  name="building-design"
                  className="sr-only"
                  checked={checked}
                  onChange={() => onChange(item.value)}
                />
                <span
                  className={cn(
                    'flex h-[70px] items-end justify-center overflow-hidden rounded-[10px] bg-[#f6f3ea]',
                    checked && 'bg-[#fdf3cf]',
                  )}
                >
                  <BuildingArt design={item.value} className="h-[62px]" />
                </span>
                <span
                  className={cn(
                    'flex items-center gap-1.5 text-[13px] leading-[18px] font-semibold',
                    checked && 'font-bold',
                  )}
                >
                  <DesignDot design={item.value} size={8} />
                  {item.label}
                </span>
                <span className="text-[11.5px] leading-[15px] break-keep text-[#6b6658]">
                  {item.description}
                </span>
                {checked && (
                  <span
                    aria-hidden="true"
                    className="absolute top-1.5 right-1.5 grid size-5 place-items-center rounded-full bg-primary text-primary-foreground"
                  >
                    <Check className="size-3" strokeWidth={3.5} />
                  </span>
                )}
              </label>
            )
          })}
        </div>
      </fieldset>
    </div>
  )
}
