// 사무실 배치의 형식. 배치는 건물 외관마다 plans/<외관>.ts에 있다(D39). 단위는 m, 방 가운데가 (0, 0), 북쪽(-z)과 동쪽(+x)이
// 유리벽, 서쪽 벽에 엘리베이터가 있다. 가구는 Kenney Furniture Kit(CC0, public/models/kenney/furniture) 모델과 직접 그리는
// 보드·화면이다(D36).
export interface OfficeZone {
  n: number
  name: string
  what: string
  x0: number
  z0: number
  x1: number
  z1: number
  floor: 'wood' | 'carpet'
  sign: [number, number]
}
export interface OfficePiece {
  zone: number
  kind: string
  model: string | null
  ui: string | null
  x: number
  z: number
  y: number
  ry: number
  s: [number, number, number] | null
  w: number
  d: number
  h: number
  tint: Record<string, string> | null
}
export type Row = [
  number,
  string,
  string | null,
  string | null,
  number,
  number,
  number,
  number,
  [number, number, number] | null,
  number,
  number,
  number,
  Record<string, string> | null,
]
export const piecesOf = (rows: Row[]): OfficePiece[] =>
  rows.map(([zone, kind, model, ui, x, z, y, ry, s, w, d, h, tint]) => ({
    zone,
    kind,
    model,
    ui,
    x,
    z,
    y,
    ry,
    s,
    w,
    d,
    h,
    tint,
  }))

/** 사무실 배치 한 벌. 건물 외관마다 하나씩 있다(D39). */
export interface OfficePlan {
  room: { w: number; d: number; h: number }
  zones: OfficeZone[]
  lounge: { x0: number; z0: number; x1: number; z1: number }
  entrance: { x: number; z: number }
  bibi: { x: number; z: number; ry: number }
  pieces: OfficePiece[]
}
