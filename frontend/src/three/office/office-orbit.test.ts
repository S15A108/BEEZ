import { assert, test } from 'vitest'
import * as THREE from 'three'
import {
  OFFICE_ZOOM,
  officeOrbitFrame,
  orbitFocus,
  orbitPan,
} from './office-orbit'

const OFFICE = {
  W: 28,
  H: 18,
  footprint: { left: 1, back: 1, right: 27, front: 15 },
}

function cameraAt(
  center: { x: number; y: number },
  width: number,
  height: number,
  zoom: number,
) {
  const frame = officeOrbitFrame(OFFICE, width, height, zoom)
  const camera = new THREE.OrthographicCamera(
    frame.left,
    frame.right,
    frame.top,
    frame.bottom,
    0.1,
    frame.far,
  )
  const aim = new THREE.Vector3(center.x, 1.3, center.y)
  camera.position
    .copy(aim)
    .add(new THREE.Vector3(frame.offset.x, frame.offset.y, frame.offset.z))
  camera.lookAt(aim)
  camera.updateMatrixWorld(true)
  return { frame, camera }
}

test('가장 멀리 빼면 방 전체가 넓은 화면과 좁은 화면에 담긴다', () => {
  for (const [width, height] of [
    [1440, 900],
    [1200, 640],
    [460, 720],
  ]) {
    const { camera } = cameraAt({ x: 14, y: 8 }, width, height, OFFICE_ZOOM.min)
    for (const x of [1, 27])
      for (const z of [1, 15])
        for (const y of [-0.5, 3.6]) {
          const projected = new THREE.Vector3(x, y, z).project(camera)
          assert.isBelow(Math.abs(projected.x), 1, `${width}x${height} x`)
          assert.isBelow(Math.abs(projected.y), 1, `${width}x${height} y`)
          assert.isBelow(Math.abs(projected.z), 1, `${width}x${height} z`)
        }
  }
})

test('확대는 지금 화면보다 가까운 쪽에서만 움직이고 사람 키가 900px 화면에서 60px을 넘는다', () => {
  const { frame } = cameraAt({ x: 14, y: 8 }, 1440, 900, OFFICE_ZOOM.start)
  assert.isAbove((900 / (frame.top - frame.bottom)) * 1.7 * 0.8, 60)
  assert.isAbove(OFFICE_ZOOM.start, OFFICE_ZOOM.min)
  assert.isAbove(OFFICE_ZOOM.max, OFFICE_ZOOM.start)
})

test('끈 곳이 커서를 그대로 따라온다', () => {
  const width = 1440,
    height = 900,
    zoom = 1.5,
    start = { x: 14, y: 8 },
    { frame, camera } = cameraAt(start, width, height, zoom),
    point = new THREE.Vector3(10, 0, 6)
  const before = point.clone().project(camera)
  const by = orbitPan(120, -40, frame, height)
  const after = point
    .clone()
    .project(
      cameraAt({ x: start.x + by.x, y: start.y + by.y }, width, height, zoom)
        .camera,
    )
  assert.closeTo(((after.x - before.x) * width) / 2, 120, 1e-6)
  assert.closeTo(((before.y - after.y) * height) / 2, -40, 1e-6)
})

test('방 어느 끝에 서도 내 캐릭터가 화면 안에 있고 초점이 방 밖으로 달아나지 않는다', () => {
  for (const zoom of [OFFICE_ZOOM.min, OFFICE_ZOOM.start, OFFICE_ZOOM.max]) {
    const frame = officeOrbitFrame(OFFICE, 1440, 900, zoom)
    for (const x of [1, 27])
      for (const y of [1, 15]) {
        const { camera } = cameraAt(
          orbitFocus({ x, y }, OFFICE, frame, zoom),
          1440,
          900,
          zoom,
        )
        const feet = new THREE.Vector3(x, 0, y).project(camera)
        assert.isBelow(Math.abs(feet.x), 1, `${zoom}: ${x},${y}`)
        assert.isBelow(Math.abs(feet.y), 1, `${zoom}: ${x},${y}`)
      }
    const far = orbitFocus({ x: 500, y: 500 }, OFFICE, frame, zoom)
    assert.isBelow(Math.hypot(far.x - 14, far.y - 8), 25)
  }
})

test('방 가운데 근처에서는 초점이 사람을 그대로 따라간다', () => {
  const frame = officeOrbitFrame(OFFICE, 1440, 900, OFFICE_ZOOM.start)
  const focus = orbitFocus({ x: 15, y: 9 }, OFFICE, frame, OFFICE_ZOOM.start)
  assert.closeTo(focus.x, 15, 1e-9)
  assert.closeTo(focus.y, 9, 1e-9)
})

test('다 빼면 어디에 서 있어도 방 가운데를 본다', () => {
  const frame = officeOrbitFrame(OFFICE, 1440, 900, OFFICE_ZOOM.min)
  assert.deepStrictEqual(
    orbitFocus({ x: 1, y: 15 }, OFFICE, frame, OFFICE_ZOOM.min),
    { x: 14, y: 8 },
  )
})
