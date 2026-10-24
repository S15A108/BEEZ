export type OfficeViewMode = 'first' | 'third'

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
    eye: { x: x / 32, y: 2.02, z: y / 32 },
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
