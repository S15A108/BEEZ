import { assert, test } from 'vitest'
import * as THREE from 'three'
import {
  OfficeOrbit,
  OFFICE_ORBIT_HOME,
  OFFICE_ORBIT_MAX_PITCH,
  OFFICE_ORBIT_MIN_PITCH,
  officeOrbitFrame,
} from './office-orbit'

test('a complete orbit keeps camera distance and projection scale constant', () => {
  const first = officeOrbitFrame(
    30,
    20,
    900,
    640,
    1,
    OFFICE_ORBIT_HOME.yaw,
    OFFICE_ORBIT_HOME.pitch,
  )
  const radius = Math.hypot(first.offset.x, first.offset.y, first.offset.z)
  for (let degrees = 0; degrees <= 720; degrees += 5) {
    const frame = officeOrbitFrame(
      30,
      20,
      900,
      640,
      1,
      (degrees * Math.PI) / 180,
      OFFICE_ORBIT_HOME.pitch,
    )
    assert.deepStrictEqual(
      [frame.left, frame.right, frame.top, frame.bottom],
      [first.left, first.right, first.top, first.bottom],
    )
    assert.isOk(
      Math.abs(
        Math.hypot(frame.offset.x, frame.offset.y, frame.offset.z) - radius,
      ) < 1e-10,
    )
  }
})

test('the office stays in the full view at every yaw and permitted elevation, on wide and narrow screens', () => {
  for (const [width, height] of [
    [1200, 640],
    [460, 720],
  ]) {
    for (const pitch of [
      OFFICE_ORBIT_MIN_PITCH,
      OFFICE_ORBIT_HOME.pitch,
      OFFICE_ORBIT_MAX_PITCH,
    ]) {
      for (let degrees = 0; degrees < 360; degrees += 10) {
        const frame = officeOrbitFrame(
          30,
          20,
          width,
          height,
          1,
          (degrees * Math.PI) / 180,
          pitch,
        )
        const camera = new THREE.OrthographicCamera(
          frame.left,
          frame.right,
          frame.top,
          frame.bottom,
          0.1,
          frame.far,
        )
        const center = new THREE.Vector3(15, 1.3, 10)
        camera.position
          .copy(center)
          .add(
            new THREE.Vector3(frame.offset.x, frame.offset.y, frame.offset.z),
          )
        camera.lookAt(center)
        camera.updateMatrixWorld(true)
        for (const x of [0, 30])
          for (const z of [0, 20])
            for (const y of [-0.5, 3.6]) {
              const projected = new THREE.Vector3(x, y, z).project(camera)
              assert.isOk(
                Math.abs(projected.x) <= 1 &&
                  Math.abs(projected.y) <= 1 &&
                  Math.abs(projected.z) <= 1,
                `clipped at ${degrees}°, pitch ${pitch}`,
              )
            }
      }
    }
  }
})

test('damping gives the same rotation at different display refresh rates', () => {
  const sixty = new OfficeOrbit(),
    fast = new OfficeOrbit()
  sixty.drag(400, 60)
  fast.drag(400, 60)
  for (let i = 0; i < 60; i++) sixty.step(1 / 60)
  for (let i = 0; i < 144; i++) fast.step(1 / 144)
  assert.isOk(Math.abs(sixty.yaw - fast.yaw) < 1e-5)
  assert.isOk(Math.abs(sixty.pitch - fast.pitch) < 1e-5)
  assert.isOk(Math.abs(sixty.yaw - (OFFICE_ORBIT_HOME.yaw - 2.4)) < 1e-5)
})

test('pole limits stay finite and reset returns by the shortest yaw after multiple full turns', () => {
  const orbit = new OfficeOrbit()
  orbit.drag((Math.PI * 6) / 0.006, 1e6)
  for (let i = 0; i < 180; i++) orbit.step(1 / 60)
  assert.strictEqual(orbit.pitch, OFFICE_ORBIT_MAX_PITCH)
  const previous = orbit.yaw
  orbit.reset()
  orbit.step(1 / 60)
  assert.isOk(
    Math.abs(orbit.yaw - previous) < 1e-5,
    'reset must not spin through three whole turns',
  )
  for (let i = 0; i < 180; i++) orbit.step(1 / 60)
  assert.isOk(Math.abs(Math.sin(orbit.yaw - OFFICE_ORBIT_HOME.yaw)) < 1e-6)
  assert.strictEqual(orbit.pitch, OFFICE_ORBIT_HOME.pitch)
  orbit.drag(0, -1e6)
  for (let i = 0; i < 180; i++) orbit.step(1 / 60)
  assert.strictEqual(orbit.pitch, OFFICE_ORBIT_MIN_PITCH)
})
