// 건물별 사무실의 재질·색(캔버스 SCR-06-rec-office-buildings v495~v504, 그림 코드 office3.html의 테마)과 시간대 빛(D31).
// 프로젝트의 건물 외관(부지 디자인 값) 다섯 개가 캔버스 다섯 안과 같은 이름이다. 값이 없으면 도시처럼 모던 화이트다.
export type OfficeDesign = 'glass' | 'brick' | 'modern' | 'tower' | 'wood'
export type OfficeTime = 'day' | 'sunset' | 'night'

export interface OfficeTheme {
  /** 오크 판재의 밝기(캔버스 oakBase). 없으면 floorColor 한 색 바닥이다. */
  oakBase?: number
  floorColor?: string
  floorRough?: number
  /** 카펫 타일 두 톤. null이면 카펫 구역도 바닥과 같다. */
  carpet: [[number, number, number], [number, number, number]] | null
  wall: string
  /** 남·서쪽 막힌 벽을 이 색 벽돌로 쌓는다(슬랫 없음). */
  brick?: string
  /** 회의실(3번 구역)을 유리 벽으로 두른다(통로 쪽 끝 1.4 m는 문). */
  glassRoom?: boolean
  /** 1인칭 천장의 나무 보 색 */
  beams?: string
  /** 높은 층(m): 창밖 거리와 다른 건물이 그만큼 아래에 있다. */
  elev?: number
  slat: string
  glass: string
  glassOpacity: number
  mullion: string
  ceiling: string
  /** 천장이 받는 빛(낮·노을). 밤은 nightCeilingGlow */
  ceilingGlow: number
  nightCeilingGlow?: number
  light: string
  sun: string
  /** Kit 재질 wood·plant 색 */
  wood: string
  plant: string
  /** 어두운 방의 밤·노을 보정(캔버스 nightLight·nightEnv·nightExposure·sunsetExposure) */
  nightLight?: number
  nightExposure?: number
  sunsetExposure?: number
}

const BASE = {
  oakBase: 205,
  carpet: [
    [196, 174, 146],
    [204, 183, 156],
  ],
  wall: '#f3f1ec',
  slat: '#b07a48',
  glass: '#dbe9ef',
  glassOpacity: 0.14,
  mullion: '#b7bbbf',
  ceiling: '#f4f3ef',
  ceilingGlow: 0.5,
  light: '#fff6e8',
  sun: '#fff1dc',
  wood: '#c99a6b',
  plant: '#4f8a3f',
} satisfies OfficeTheme

export const OFFICE_THEMES: Record<OfficeDesign, OfficeTheme> = {
  // 유리 오피스: 밝은 회색 바닥, 하늘색 카펫, 흰 벽, 맑은 유리
  glass: {
    ...BASE,
    oakBase: undefined,
    floorColor: '#d9dcdc',
    floorRough: 0.35,
    carpet: [
      [176, 190, 200],
      [186, 199, 208],
    ],
    wall: '#f4f6f7',
    slat: '#9fb3c2',
    glass: '#bfe0f2',
    glassOpacity: 0.24,
    mullion: '#7d858c',
    light: '#f4f8ff',
    sun: '#ffffff',
    glassRoom: true,
  },
  // 붉은 벽돌: 중간 오크, 흰 천장, 검은 창틀, 따뜻한 빛
  brick: {
    ...BASE,
    oakBase: 174,
    carpet: [
      [150, 132, 118],
      [160, 141, 126],
    ],
    wall: '#efe7dc',
    brick: '#a8543a',
    mullion: '#2b2b2b',
    ceiling: '#ebe6df',
    ceilingGlow: 0.15,
    glass: '#e6eef0',
    glassOpacity: 0.12,
    light: '#ffeacc',
    sun: '#ffe2bd',
    wood: '#8a5a3b',
  },
  // 모던 화이트: 흰 갤러리에 노랑 하나
  modern: {
    ...BASE,
    oakBase: undefined,
    floorColor: '#ecebe7',
    floorRough: 0.3,
    carpet: null,
    wall: '#ffffff',
    slat: '#f3c530',
    mullion: '#e2e2e2',
    light: '#ffffff',
    sun: '#fff8ef',
    glassRoom: true,
    wood: '#f1efe9',
    plant: '#6f8f5c',
  },
  // 다크 타워: 짙은 회색 바닥·벽·천장, 어두운 유리
  tower: {
    ...BASE,
    oakBase: undefined,
    floorColor: '#5b5c63',
    floorRough: 0.45,
    carpet: [
      [86, 88, 94],
      [94, 96, 102],
    ],
    wall: '#4a4c53',
    slat: '#b08a5a',
    glass: '#8fa3b3',
    glassOpacity: 0.22,
    mullion: '#26282c',
    ceiling: '#5a5c62',
    ceilingGlow: 0.85,
    nightCeilingGlow: 0.45,
    light: '#ffe9c9',
    sun: '#f3e6d0',
    glassRoom: true,
    wood: '#5a4232',
    nightLight: 12,
    nightExposure: 1.05,
    elev: 60,
  },
  // 목조 스튜디오: 오크, 베이지 카펫, 나무 슬랫·창틀, 따뜻한 빛
  wood: {
    ...BASE,
    oakBase: 172,
    wall: '#efe2cc',
    slat: '#9a6a3f',
    beams: '#a8794e',
    mullion: '#8a6440',
    ceiling: '#efe2cc',
    ceilingGlow: 0.15,
    light: '#ffd9a8',
    sun: '#ffe2bd',
    wood: '#b98a52',
    sunsetExposure: 1.1,
  },
}

export const officeDesignOf = (design?: string): OfficeDesign =>
  design && design in OFFICE_THEMES ? (design as OfficeDesign) : 'modern'

/**
 * 한국 시각의 시간대. 7시부터 낮, 17시부터 노을, 19시부터 밤이다.
 * ponytail: 계절과 상관없는 고정 시각이다. 계절마다 해 지는 시각을 맞추려면 날짜별 일몰표를 둔다.
 */
export function officeTimeAt(now: Date): OfficeTime {
  const hour = Number(
    new Intl.DateTimeFormat('en-US', {
      hour: 'numeric',
      hourCycle: 'h23',
      timeZone: 'Asia/Seoul',
    }).format(now),
  )
  return hour >= 7 && hour < 17
    ? 'day'
    : hour >= 17 && hour < 19
      ? 'sunset'
      : 'night'
}

/**
 * 시간대별 하늘·해·방 빛(캔버스 office3.html SKY·SUN·HEM·lightK를 이 렌더러의 빛 세기에 맞춤). sun의 x·y·z는 해가 비추는
 * 곳에서 해까지의 방향(타일, 북쪽 = -z)이고, 색이 null이면 건물 테마의 햇빛 색이다. room은 천장 등(방 빛)의 세기다.
 */
export const OFFICE_TIMES: Record<
  OfficeTime,
  {
    sky: string
    sun: [
      color: string | null,
      intensity: number,
      x: number,
      y: number,
      z: number,
    ]
    hemisphere: [sky: string, ground: string, intensity: number]
    fill: number
    room: number
    outside: number
    lamps: number
  }
> = {
  day: {
    sky: '#8fcff5',
    sun: [null, 2.2, -8, 26, 5],
    hemisphere: ['#fff1dc', '#c9b89a', 1.5],
    fill: 0.55,
    room: 0,
    outside: 1.75,
    lamps: 0.65,
  },
  sunset: {
    sky: '#ef9a66',
    sun: ['#ff8a4a', 2.4, -26, 6, -24],
    hemisphere: ['#ffd9b8', '#6b5a7a', 1.05],
    fill: 0.3,
    room: 0.4,
    outside: 1.1,
    lamps: 1.1,
  },
  night: {
    sky: '#16233f',
    sun: ['#9bb4ff', 0.15, 10, 30, -20],
    hemisphere: ['#3b4a72', '#1a1a22', 0.2],
    fill: 0.05,
    room: 1.45,
    outside: 0.3,
    lamps: 1.6,
  },
}
