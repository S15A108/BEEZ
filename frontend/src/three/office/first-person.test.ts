import { assert, test } from 'vitest'
import {
  FirstPersonLook,
  firstPersonFrame,
  firstPersonMove,
} from './first-person'
import { moveContinuous } from './motion'

test('W follows the viewing direction and D strafes right at each cardinal heading', () => {
  for (const yaw of [0, Math.PI / 2, Math.PI, -Math.PI / 2]) {
    const frame = firstPersonFrame(240, 144, yaw, 0)
    const forward = firstPersonMove({ x: 0, y: -1 }, yaw)
    assert.isOk(Math.abs(forward.x - frame.forward.x) < 1e-10)
    assert.isOk(Math.abs(forward.y - frame.forward.z) < 1e-10)
    const right = firstPersonMove({ x: 1, y: 0 }, yaw)
    assert.isOk(Math.abs(forward.x * right.x + forward.y * right.y) < 1e-10)
    // Camera right = forward cross world-up, so D must not be mirrored.
    assert.isOk(Math.abs(right.x + frame.forward.z) < 1e-10)
    assert.isOk(Math.abs(right.y - frame.forward.x) < 1e-10)
  }
})

test('turning does not change movement speed or diagonal normalization, including multiple full turns', () => {
  for (let angle = -720; angle <= 720; angle += 7) {
    const yaw = (angle * Math.PI) / 180
    const direction = firstPersonMove(
      { x: Math.SQRT1_2, y: -Math.SQRT1_2 },
      yaw,
    )
    assert.isOk(Math.abs(Math.hypot(direction.x, direction.y) - 1) < 1e-10)
  }
})

test('camera eye follows the player without changing height or flying when looking up or down', () => {
  for (const pitch of [-1.3, 0, 1.3]) {
    const frame = firstPersonFrame(320, 160, 1, pitch)
    assert.deepStrictEqual(frame.eye, { x: 10, y: 2.02, z: 5 })
    assert.isOk(
      Math.abs(
        Math.hypot(frame.forward.x, frame.forward.y, frame.forward.z) - 1,
      ) < 1e-10,
    )
  }
})

test('first-person movement still uses the existing furniture collision boundary', () => {
  const input = firstPersonMove({ x: 0, y: -1 }, Math.PI / 2)
  const next = moveContinuous(
    { x: 20, y: 20 },
    { x: input.x * 100, y: input.y * 100 },
    (x) => x < 40,
  )
  assert.isOk(next.x < 40)
  assert.isOk(Math.abs(next.y - 20) < 1e-8)
})

test('looking respects pitch limits, restores a supplied heading and is refresh-rate independent', () => {
  const slow = new FirstPersonLook(),
    fast = new FirstPersonLook()
  slow.face(Math.PI / 2)
  fast.face(Math.PI / 2)
  slow.drag(200, 1e5)
  fast.drag(200, 1e5)
  for (let i = 0; i < 60; i++) slow.step(1 / 60)
  for (let i = 0; i < 144; i++) fast.step(1 / 144)
  assert.isOk(Math.abs(slow.yaw - fast.yaw) < 1e-6)
  assert.isOk(Math.abs(slow.pitch - fast.pitch) < 1e-6)
  assert.isOk(slow.pitch < Math.PI / 2)
  assert.isOk(Math.abs(slow.yaw - (Math.PI / 2 - 0.8)) < 1e-6)
  slow.face(0)
  assert.strictEqual(slow.yaw, 0)
  assert.strictEqual(slow.pitch, -0.08)
})

test('first-person view turns with the pointer in all four directions at every heading', () => {
  for (const yaw of [
    0,
    Math.PI / 2,
    Math.PI,
    -Math.PI / 2,
    Math.PI * 4 + 0.3,
  ]) {
    for (const [dx, dy] of [
      [50, 0],
      [-50, 0],
      [0, 25],
      [0, -25],
      [50, -25],
      [-50, 25],
    ]) {
      const look = new FirstPersonLook()
      look.face(yaw)
      const pitch = look.pitch
      const right = { x: -Math.cos(yaw), y: 0, z: Math.sin(yaw) }
      const up = {
        x: -Math.sin(yaw) * Math.sin(pitch),
        y: Math.cos(pitch),
        z: -Math.cos(yaw) * Math.sin(pitch),
      }
      look.drag(dx, dy)
      look.step(1)
      const { forward } = firstPersonFrame(240, 144, look.yaw, look.pitch)
      const horizontal =
        forward.x * right.x + forward.y * right.y + forward.z * right.z
      const vertical = forward.x * up.x + forward.y * up.y + forward.z * up.z
      if (dx)
        assert.isOk(
          horizontal * dx > 0,
          'right/left pointer motion turns toward camera right/left',
        )
      if (dy)
        assert.isOk(
          vertical * dy < 0,
          'up/down pointer motion turns toward camera up/down',
        )
    }
  }
})
