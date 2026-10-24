export const OFFICE_ORBIT_HOME = {
  yaw: Math.atan2(22, 35),
  pitch: Math.atan2(30, Math.hypot(22, 35)),
}
export const OFFICE_ORBIT_MIN_PITCH = Math.PI / 12
export const OFFICE_ORBIT_MAX_PITCH = (Math.PI * 4) / 9

/** Damped spherical orbit; yaw remains continuous across 0/360 degrees. */
export class OfficeOrbit {
  yaw = OFFICE_ORBIT_HOME.yaw
  pitch = OFFICE_ORBIT_HOME.pitch
  private targetYaw = this.yaw
  private targetPitch = this.pitch

  drag(dx: number, dy: number) {
    this.targetYaw -= dx * 0.006
    this.targetPitch = Math.max(
      OFFICE_ORBIT_MIN_PITCH,
      Math.min(OFFICE_ORBIT_MAX_PITCH, this.targetPitch + dy * 0.006),
    )
  }

  reset() {
    this.targetYaw =
      this.yaw +
      Math.atan2(
        Math.sin(OFFICE_ORBIT_HOME.yaw - this.yaw),
        Math.cos(OFFICE_ORBIT_HOME.yaw - this.yaw),
      )
    this.targetPitch = OFFICE_ORBIT_HOME.pitch
  }

  step(seconds: number) {
    const blend = 1 - Math.exp(-Math.max(0, seconds) * 14)
    this.yaw += (this.targetYaw - this.yaw) * blend
    this.pitch += (this.targetPitch - this.pitch) * blend
    if (Math.abs(this.targetYaw - this.yaw) < 1e-6) this.yaw = this.targetYaw
    if (Math.abs(this.targetPitch - this.pitch) < 1e-6)
      this.pitch = this.targetPitch
  }
}

/** One rotation-independent bounding sphere controls framing, rather than a new projected AABB at every angle. */
export function officeOrbitFrame(
  floorWidth: number,
  floorDepth: number,
  width: number,
  height: number,
  zoom: number,
  yaw: number,
  pitch: number,
) {
  const sphereRadius = Math.hypot(floorWidth / 2, floorDepth / 2, 2.1)
  const distance = sphereRadius * 2.7 + 8
  const aspect = Math.max(1, width) / Math.max(1, height)
  const viewHeight =
    (sphereRadius * 2.12) / Math.min(1, aspect) / Math.max(0.5, zoom)
  const horizontal = distance * Math.cos(pitch)
  return {
    left: (-viewHeight * aspect) / 2,
    right: (viewHeight * aspect) / 2,
    top: viewHeight / 2,
    bottom: -viewHeight / 2,
    far: distance + sphereRadius + 20,
    offset: {
      x: horizontal * Math.sin(yaw),
      y: distance * Math.sin(pitch),
      z: horizontal * Math.cos(yaw),
    },
  }
}
