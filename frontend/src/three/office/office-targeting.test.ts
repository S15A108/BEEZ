import { assert, test } from 'vitest'
import * as THREE from 'three'
import {
  isOfficePickVisible,
  pickOfficeInteraction,
  pickOfficeObject,
  setOfficeViewVisibility,
} from './office-targeting'

const box = (z: number) => {
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(1, 1, 1),
    new THREE.MeshBasicMaterial(),
  )
  mesh.position.z = z
  return mesh
}
const ray = () =>
  new THREE.Raycaster(new THREE.Vector3(), new THREE.Vector3(0, 0, -1))

test('the center ray chooses the object in view, rather than an off-center closer colleague', () => {
  const scene = new THREE.Scene(),
    colleague = box(-2),
    screen = box(-5)
  colleague.position.x = 1.5
  colleague.userData.entityId = 'nearby'
  scene.add(colleague, screen)
  scene.updateMatrixWorld(true)
  assert.strictEqual(
    pickOfficeObject(ray(), [colleague, screen])?.object,
    screen,
  )
  screen.position.x = 3
  scene.updateMatrixWorld(true)
  assert.strictEqual(pickOfficeObject(ray(), [colleague, screen]), undefined)
})

test('the first visible surface blocks objects behind it, including glass partitions and walls', () => {
  const wall = box(-2),
    target = box(-4),
    scene = new THREE.Scene()
  scene.add(wall, target)
  scene.updateMatrixWorld(true)
  assert.strictEqual(pickOfficeObject(ray(), [target, wall])?.object, wall)
  wall.visible = false
  assert.strictEqual(pickOfficeObject(ray(), [target, wall])?.object, target)
})

test('decorative furniture has no interaction, even when it has a furniture label', () => {
  const scene = new THREE.Scene(),
    decoration = new THREE.Group(),
    mesh = box(-2)
  decoration.userData.officeFurniture = { kind: 'sofa', x: 2, y: 2, w: 2, h: 1 }
  decoration.userData.aimRoot = true
  decoration.add(mesh)
  scene.add(decoration)
  scene.updateMatrixWorld(true)
  const interaction = pickOfficeInteraction(ray(), [decoration])
  assert.strictEqual(interaction, null)
})

test('functional screens keep their selected task and UV coordinates, while furniture blocks actions behind it', () => {
  const scene = new THREE.Scene(),
    furniture = box(-2),
    screen = box(-4)
  const task = {
    title: '작업 보기',
    detail: '진행 중',
    target: { type: 'task' as const, id: 'task-1' },
  }
  let hits = 0
  screen.userData.workSurface = {
    hit(u: number, v: number) {
      hits++
      assert.strictEqual(u, 0.5)
      assert.strictEqual(v, 0.5)
      return task
    },
  }
  scene.add(furniture, screen)
  scene.updateMatrixWorld(true)
  assert.strictEqual(pickOfficeInteraction(ray(), [screen, furniture]), null)
  assert.strictEqual(hits, 0)
  furniture.position.x = 3
  scene.updateMatrixWorld(true)
  const interaction = pickOfficeInteraction(ray(), [screen, furniture])
  assert.strictEqual(interaction?.hit.object, screen)
  assert.strictEqual(interaction?.workHit, task)
  assert.strictEqual(hits, 1)
  screen.visible = false
  assert.strictEqual(pickOfficeInteraction(ray(), [screen, furniture]), null)
})

test('a visible colleague remains actionable through their body meshes, but a wall blocks conversation', () => {
  const scene = new THREE.Scene(),
    colleague = new THREE.Group(),
    body = box(-4),
    wall = box(-2)
  colleague.userData.entityId = 'colleague-1'
  colleague.add(body)
  scene.add(wall, colleague)
  scene.updateMatrixWorld(true)
  assert.strictEqual(pickOfficeInteraction(ray(), [colleague, wall]), null)
  wall.visible = false
  assert.strictEqual(
    pickOfficeInteraction(ray(), [colleague, wall])?.entityId,
    'colleague-1',
  )
  colleague.visible = false
  assert.strictEqual(pickOfficeInteraction(ray(), [colleague, wall]), null)
})

test('hidden ancestors, the invisible first-person player and noninteractive beam meshes are skipped', () => {
  const scene = new THREE.Scene(),
    hidden = new THREE.Group(),
    player = box(-1),
    beam = box(-2),
    target = box(-5)
  hidden.visible = false
  hidden.add(player)
  beam.userData.ignorePicking = true
  scene.add(hidden, beam, target)
  scene.updateMatrixWorld(true)
  assert.strictEqual(isOfficePickVisible(player), false)
  assert.strictEqual(
    pickOfficeObject(ray(), [hidden, beam, target])?.object,
    target,
  )
})

test('only the projector device and beam disappear in third person; the screen survives repeated view changes', () => {
  const root = new THREE.Group(),
    screen = box(-4),
    device = new THREE.Group(),
    beam = box(-2)
  device.userData.firstPersonOnly = true
  device.add(beam)
  root.add(screen, device)
  for (const firstPerson of [false, true, false, true]) {
    setOfficeViewVisibility(root, firstPerson)
    assert.strictEqual(screen.visible, true)
    assert.strictEqual(device.visible, firstPerson)
    assert.strictEqual(isOfficePickVisible(beam), firstPerson)
  }
})
