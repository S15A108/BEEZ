import { assert, test } from 'vitest'
import * as THREE from 'three'
import { ALL_KEYS, makeBuilding } from './building'
import { makeDreamFloor } from './dream-floor'
import { OfficeCity, CITY_WALK_RADIUS } from './office-city'
import { streetSpawn } from './OutdoorStreetRenderer'
import { nearOfficeExit } from './office-exit'
import { gridWalkable, moveContinuous, smoothRoute } from './motion'
import { findPath } from './path'
import { pickOfficeInteraction } from './office-targeting'

const bounds = { left: 1, back: 1, right: 29, front: 16 }

test('small and expanded offices have a reachable elevator with a free return landing and a solid cabin', () => {
  for (const population of [
    { dev: 1, lead: 1, humandesk: 1 },
    { dev: 12, lead: 1, humandesk: 6, review: 2, hr: 2, control: 2 },
  ]) {
    const base = makeBuilding(ALL_KEYS, 'glass', null, population).floors[1],
      floor = makeDreamFloor(base, 4),
      exit = floor.outdoorExit!
    assert.isOk(exit)
    assert.strictEqual(makeDreamFloor(base, 3).outdoorExit, undefined)
    const free = (x: number, y: number) =>
      gridWalkable({ x, y: y - 4 }, floor.blocked, 32, 6) &&
      floor.grid[Math.floor((y - 4) / 32)]?.[Math.floor(x / 32)] !== '#'
    assert.isOk(free(exit.landing.x, exit.landing.y))
    assert.strictEqual(
      free((exit.x + exit.w / 2) * 32, (exit.y + 0.5) * 32),
      false,
    )
    const start = { x: 7.5 * 32, y: 4.5 * 32 }
    const cells = findPath(
      floor.blocked,
      Math.floor(start.x / 32),
      Math.floor(start.y / 32),
      Math.floor(exit.landing.x / 32),
      Math.floor(exit.landing.y / 32),
    )
    const route = smoothRoute(
      start,
      [
        ...cells.map(([x, y]) => ({ x: x * 32 + 16, y: y * 32 + 27 })),
        exit.landing,
      ],
      free,
    )
    assert.isOk(route.length)
    assert.deepStrictEqual(route.at(-1), exit.landing)
    let point = { ...start }
    for (const destination of route) {
      const delta = { x: destination.x - point.x, y: destination.y - point.y }
      point = moveContinuous(point, delta, free)
      assert.isOk(
        Math.hypot(point.x - destination.x, point.y - destination.y) < 0.01,
      )
    }
    assert.strictEqual(nearOfficeExit(point, exit), true)
    assert.strictEqual(nearOfficeExit(start, exit), false)
  }
})

test('the elevator can be aimed at, while an intervening wall blocks its interaction', () => {
  const root = new THREE.Group(),
    door = new THREE.Mesh(
      new THREE.BoxGeometry(2, 3, 0.2),
      new THREE.MeshBasicMaterial(),
    ),
    wall = new THREE.Mesh(
      new THREE.BoxGeometry(3, 3, 0.2),
      new THREE.MeshBasicMaterial(),
    )
  root.userData.outdoorExit = true
  door.position.z = -4
  root.add(door)
  wall.position.z = -2
  const scene = new THREE.Scene()
  scene.add(root, wall)
  scene.updateMatrixWorld(true)
  const ray = new THREE.Raycaster(
    new THREE.Vector3(),
    new THREE.Vector3(0, 0, -1),
  )
  assert.strictEqual(pickOfficeInteraction(ray, [root, wall]), null)
  wall.visible = false
  assert.strictEqual(
    pickOfficeInteraction(ray, [root, wall])?.outdoorExit,
    true,
  )
})

test('the street spawn is on the pavement and building footprints, nearby fixtures and district limits block walking', () => {
  const city = new OfficeCity(bounds)
  try {
    const spawn = streetSpawn(bounds)
    assert.strictEqual(city.canWalk(spawn.x / 32, spawn.y / 32), true)
    assert.strictEqual(city.canWalk(15, 8), false)
    assert.strictEqual(city.canWalk(15 + CITY_WALK_RADIUS, 8), false)
    assert.strictEqual(city.canWalk(NaN, 0), false)
    city.root.updateMatrixWorld(true)
    let checked = 0
    const matrix = new THREE.Matrix4(),
      world = new THREE.Matrix4(),
      box = new THREE.Box3(),
      center = new THREE.Vector3()
    city.root.traverse((object) => {
      if (
        !(object instanceof THREE.InstancedMesh) ||
        !object.userData.cityBuildings ||
        object.userData.cityLod !== 'near'
      )
        return
      object.geometry.computeBoundingBox()
      for (let i = 0; i < object.count && checked < 20; i++) {
        object.getMatrixAt(i, matrix)
        world.multiplyMatrices(object.matrixWorld, matrix)
        box.copy(object.geometry.boundingBox!).applyMatrix4(world)
        box.getCenter(center)
        if (
          box.min.y > -14 ||
          box.max.y < -13 ||
          box.getSize(new THREE.Vector3()).x < 2
        )
          continue
        assert.strictEqual(city.canWalk(center.x, center.z), false)
        checked++
      }
    })
    assert.isOk(checked > 4)
  } finally {
    city.dispose()
  }
})

test('street traffic yields ahead of a walker, queues with a gap, and resumes when the pavement is clear', () => {
  const city = new OfficeCity(bounds),
    camera = new THREE.Vector3(15, 2, 20)
  try {
    const front = city.root.children.find(
      (item) => item.userData.citySector === 'front',
    )!
    const traffic = front.children.find((item) => item.userData.cityTraffic)!
    const chassis = traffic.children.find(
      (item) => item instanceof THREE.InstancedMesh,
    ) as THREE.InstancedMesh
    const at = (index: number) => {
      const matrix = new THREE.Matrix4()
      chassis.getMatrixAt(index * 2, matrix)
      return new THREE.Vector3().setFromMatrixPosition(matrix)
    }
    city.update(0, camera, true)
    const initial = at(0),
      cycle = Math.abs(at(1).x - at(0).x) * 3
    const walker = front.localToWorld(
      new THREE.Vector3(initial.x - 1.8, 0, initial.z),
    )
    for (let i = 1; i <= 200; i++)
      city.update(i / 10, camera, true, { x: walker.x, z: walker.z })
    assert.strictEqual(at(0).x, initial.x)
    const positions = [at(0).x, at(1).x, at(2).x]
    for (let i = 0; i < 3; i++)
      for (let j = i + 1; j < 3; j++)
        assert.isOk(
          Math.min(
            Math.abs(positions[i] - positions[j]),
            cycle - Math.abs(positions[i] - positions[j]),
          ) > 2.3,
        )
    const car = front.localToWorld(at(0))
    assert.strictEqual(city.canWalk(car.x, car.z), false)
    for (let i = 201; i <= 210; i++)
      city.update(i / 10, camera, true, { x: 15, z: 19.2 })
    assert.isOk(Math.abs(at(0).x - initial.x) > 1.5)
  } finally {
    city.dispose()
  }
})
