/** Fixed-step rigid-body fall against a horizontal floor, with bounce and angular damping. */
export interface DropBody {
  y: number
  vy: number
  angle: number
  omega: number
  halfHeight: number
  radius: number
  impacts: number
  sleeping: boolean
}
export function dropSupport(body: DropBody) {
  return (
    Math.abs(Math.cos(body.angle)) * body.halfHeight +
    Math.abs(Math.sin(body.angle)) * body.radius
  )
}
export function stepDrop(body: DropBody, dt: number) {
  if (body.sleeping) return
  body.vy -= 9.81 * dt
  body.y += body.vy * dt
  if (body.impacts) body.omega += (-18 * body.angle - 4.8 * body.omega) * dt
  else body.omega *= Math.exp(-0.18 * dt)
  body.angle += body.omega * dt
  const support = dropSupport(body)
  if (body.y <= support + 0.035) {
    body.y = support + 0.035
    if (body.vy < 0) {
      body.vy = -body.vy * 0.28
      body.omega *= 0.55
      body.angle = Math.atan2(Math.sin(body.angle), Math.cos(body.angle))
      body.impacts++
    }
    if (
      Math.abs(body.vy) < 0.16 &&
      Math.abs(body.angle) < 0.008 &&
      Math.abs(body.omega) < 0.04
    ) {
      body.y = body.halfHeight + 0.035
      body.angle = 0
      body.omega = 0
      body.vy = 0
      body.sleeping = true
    }
  }
}
