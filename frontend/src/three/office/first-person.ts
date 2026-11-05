export type OfficeViewMode = 'first' | 'third'

// 보드처럼 팀원 셋을 같은 키로 맞춘다. 1.5타일이면 가장 넓은 사람도 폭이 약 1타일이라 개인 거리 34px(약 1.06타일)에서 겹치지 않는다.
export const FIGURE_HEIGHT = 1.5
// 캔버스에서 팀원은 1.7 m, 에이전트는 안테나 끝까지 1.74 m(모델 단위 m), 비비는 1.35 m다(v478). 팀원 1.5타일을 1.7 m로 본다.
export const METER = FIGURE_HEIGHT / 1.7
// 1인칭 카메라는 키 1.7 m 팀원의 눈높이(1.6 m)에 둔다.
const EYE_HEIGHT = 1.6 * METER

/** Horizontal movement follows the camera's right/forward axes, without flying when looking up. */
export function firstPersonMove(input: { x: number; y: number }, yaw: number) {
  return {
    x: -input.x * Math.cos(yaw) - input.y * Math.sin(yaw),
    y: input.x * Math.sin(yaw) - input.y * Math.cos(yaw),
  }
}

export function firstPersonFrame(
  x: number,
  y: number,
  yaw: number,
  pitch: number,
) {
  const horizontal = Math.cos(pitch)
  return {
    eye: { x: x / 32, y: EYE_HEIGHT, z: y / 32 },
    forward: {
      x: Math.sin(yaw) * horizontal,
      y: Math.sin(pitch),
      z: Math.cos(yaw) * horizontal,
    },
  }
}

export class FirstPersonLook {
  yaw = -Math.PI / 2
  pitch = -0.08
  private targetYaw = this.yaw
  private targetPitch = this.pitch

  face(yaw: number) {
    this.yaw = this.targetYaw = yaw
    this.pitch = this.targetPitch = -0.08
  }
  drag(dx: number, dy: number) {
    this.targetYaw -= dx * 0.004
    this.targetPitch = Math.max(
      -Math.PI * 0.42,
      Math.min(Math.PI * 0.42, this.targetPitch - dy * 0.004),
    )
  }
  level() {
    this.targetPitch = -0.08
  }
  step(seconds: number) {
    const blend = 1 - Math.exp(-Math.max(0, seconds) * 20)
    this.yaw += (this.targetYaw - this.yaw) * blend
    this.pitch += (this.targetPitch - this.pitch) * blend
    if (Math.abs(this.targetYaw - this.yaw) < 1e-6) this.yaw = this.targetYaw
    if (Math.abs(this.targetPitch - this.pitch) < 1e-6)
      this.pitch = this.targetPitch
  }
}
