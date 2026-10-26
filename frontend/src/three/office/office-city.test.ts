import { assert, test } from 'vitest'
import * as THREE from 'three'
import { OfficeCity } from './office-city'

const bounds = { left: 1, back: 1, right: 29, front: 17 }
const center = new THREE.Vector3(15, 2, 9)

function trafficTransforms(root: THREE.Object3D) {
  const values: number[] = []
  root.traverse((object) => {
    if (!object.userData.cityTraffic) return
    object.traverse((part) => {
      if (part instanceof THREE.InstancedMesh)
        values.push(...part.instanceMatrix.array)
    })
  })
  return values
}

/** A district-wide instanced batch can surround the office without any individual building entering it. */
function meshInstanceBounds(
  root: THREE.Object3D,
  visit: (box: THREE.Box3, mesh: THREE.Mesh, instance: number) => void,
) {
  root.updateMatrixWorld(true)
  const localMatrix = new THREE.Matrix4(),
    worldMatrix = new THREE.Matrix4()
  const box = new THREE.Box3()
  root.traverse((object) => {
    if (!(object instanceof THREE.Mesh)) return
    object.geometry.computeBoundingBox()
    if (!object.geometry.boundingBox) return
    const count = object instanceof THREE.InstancedMesh ? object.count : 1
    for (let instance = 0; instance < count; instance++) {
      if (object instanceof THREE.InstancedMesh) {
        object.getMatrixAt(instance, localMatrix)
        worldMatrix.multiplyMatrices(object.matrixWorld, localMatrix)
      } else worldMatrix.copy(object.matrixWorld)
      box.copy(object.geometry.boundingBox).applyMatrix4(worldMatrix)
      visit(box, object, instance)
    }
  })
}

test('a complete third-person orbit opens foreground city blocks while preserving the distant city', () => {
  const city = new OfficeCity(bounds)
  try {
    const sectors = city.root.children.filter(
      (object) => object.userData.citySector,
    )
    assert.strictEqual(
      sectors.length,
      4,
      'the office needs a city view on every side',
    )
    for (let degrees = 0; degrees < 360; degrees += 5) {
      const angle = (degrees * Math.PI) / 180
      const direction = new THREE.Vector3(Math.sin(angle), 0, Math.cos(angle))
      const camera = center.clone().addScaledVector(direction, 50)
      city.update(12, camera, false)
      for (const sector of sectors) {
        const outward = sector.position.clone().sub(center).setY(0).normalize()
        const facingCamera = outward.dot(direction)
        if (facingCamera > 0.4)
          assert.strictEqual(
            sector.visible,
            false,
            `foreground obstruction at ${degrees} degrees`,
          )
        if (facingCamera < -0.4)
          assert.strictEqual(
            sector.visible,
            true,
            `missing distant city at ${degrees} degrees`,
          )
      }
      const visible = sectors.filter((sector) => sector.visible).length
      assert.isOk(
        visible > 0 && visible < sectors.length,
        `city must stay visible and cut away at ${degrees} degrees`,
      )
    }
  } finally {
    city.dispose()
  }
})

test('switching to first person restores all window views after any orbit direction', () => {
  const city = new OfficeCity(bounds)
  try {
    const sectors = city.root.children.filter(
      (object) => object.userData.citySector,
    )
    for (const direction of [
      new THREE.Vector3(50, 0, 50),
      new THREE.Vector3(-50, 0, -50),
    ]) {
      city.update(20, center.clone().add(direction), false)
      assert.isOk(sectors.some((sector) => !sector.visible))
      city.update(20, center, true)
      assert.isOk(
        sectors.every((sector) => sector.visible),
        'first-person windows must overlook all four city blocks',
      )
    }
  } finally {
    city.dispose()
  }
})

test('the continuous city ground extends beneath the full district', () => {
  const city = new OfficeCity(bounds)
  try {
    const ground = city.root.getObjectByName('first-person-ground')
    assert.isOk(ground instanceof THREE.Mesh)
    city.update(0, center, true)
    assert.strictEqual(ground.visible, true)
    city.root.updateMatrixWorld(true)
    const groundBounds = new THREE.Box3().setFromObject(ground)
    assert.isOk(
      groundBounds.min.x < bounds.left - 1000 &&
        groundBounds.max.x > bounds.right + 1000,
    )
    assert.isOk(
      groundBounds.min.z < bounds.back - 1000 &&
        groundBounds.max.z > bounds.front + 1000,
    )
    assert.isOk(
      groundBounds.max.y < -14.63,
      'continuous ground must stay below the existing street slabs',
    )
  } finally {
    city.dispose()
  }
})

test('the original boulevards continue as asphalt beyond all eight street ends', () => {
  const city = new OfficeCity(bounds)
  try {
    city.update(0, center, true)
    city.root.updateMatrixWorld(true)
    const roads: THREE.Object3D[] = []
    city.root.traverse((object) => {
      if (object.userData.citySurface === 'road') roads.push(object)
    })
    assert.isOk(
      roads.length > 0,
      'an extended ground plane alone must not replace the street network',
    )
    const ray = new THREE.Raycaster(
      new THREE.Vector3(),
      new THREE.Vector3(0, -1, 0),
    )
    const origins: THREE.Vector3[] = []
    for (let distance = 20; distance <= 800; distance += 20) {
      for (const z of [bounds.back - 8.4, bounds.front + 8.4]) {
        origins.push(new THREE.Vector3(bounds.left - distance, 20, z))
        origins.push(new THREE.Vector3(bounds.right + distance, 20, z))
      }
      for (const x of [bounds.left - 8.4, bounds.right + 8.4]) {
        origins.push(new THREE.Vector3(x, 20, bounds.back - distance))
        origins.push(new THREE.Vector3(x, 20, bounds.front + distance))
      }
    }
    const grid = city.root.userData.districtGrid
    assert.isOk(
      grid?.reach >= 800 && grid.spacing > 0,
      'the district must continue to the distant skyline',
    )
    // A pair of long roads would still leave empty map corners. Sample intersections
    // well outside the original block to require a complete two-dimensional street grid.
    for (const step of [2, 6, 12, 18]) {
      for (const x of [
        grid.leftRoad - grid.spacing * step,
        grid.rightRoad + grid.spacing * step,
      ]) {
        for (const z of [
          grid.backRoad - grid.spacing * step,
          grid.frontRoad + grid.spacing * step,
        ]) {
          origins.push(new THREE.Vector3(x, 20, z))
        }
      }
    }
    for (const origin of origins) {
      ray.ray.origin.copy(origin)
      const hit = ray.intersectObjects(roads, false)[0]
      assert.isOk(hit, `the boulevard ends before (${origin.x}, ${origin.z})`)
      assert.isOk(
        hit.point.y > -14.7 && hit.point.y < -14.4,
        'street continuation must meet the existing street level',
      )
    }
  } finally {
    city.dispose()
  }
})

test('instanced buildings fill near, middle and far views in every direction with a bounded rendering cost', () => {
  const city = new OfficeCity(bounds)
  try {
    city.update(0, center, true)
    const coverage = new Map<string, Set<number>>()
    let districtMeshes = 0,
      districtTriangles = 0
    city.root.traverse((object) => {
      if (!(object instanceof THREE.Mesh) || !object.userData.cityBuildings)
        return
      assert.isOk(
        object instanceof THREE.InstancedMesh,
        'extended building details must share draw calls',
      )
      districtMeshes++
      const triangles =
        (object.geometry.index?.count ??
          object.geometry.getAttribute('position').count) / 3
      districtTriangles += triangles * object.count
    })
    meshInstanceBounds(city.root, (box, object) => {
      if (!object.userData.cityBuildings || box.max.y < 0) return
      const x = (box.min.x + box.max.x) / 2 - center.x
      const z = (box.min.z + box.max.z) / 2 - center.z
      const distance = Math.hypot(x, z)
      const band = distance < 160 ? 'near' : distance < 400 ? 'mid' : 'far'
      if (distance < 35 || distance > 800 || (band === 'far' && distance < 600))
        return
      const octant =
        Math.floor((Math.atan2(z, x) + Math.PI + Math.PI / 8) / (Math.PI / 4)) %
        8
      if (!coverage.has(band)) coverage.set(band, new Set())
      coverage.get(band)!.add(octant)
    })
    for (const band of ['near', 'mid', 'far']) {
      assert.strictEqual(
        coverage.get(band)?.size,
        8,
        `${band} buildings must surround all four sides and diagonal gaps`,
      )
    }
    assert.isOk(
      districtMeshes > 0 && districtMeshes <= 64,
      'city expansion must use a fixed set of instanced batches',
    )
    assert.isOk(
      districtTriangles < 1_000_000,
      'distant buildings need fewer details than the foreground facades',
    )
  } finally {
    city.dispose()
  }
})

test('city glazing and trim stay matte as the camera moves', () => {
  const city = new OfficeCity(bounds)
  try {
    const materials = new Set<THREE.MeshStandardMaterial>()
    city.root.traverse((object) => {
      if (!(object instanceof THREE.Mesh)) return
      for (const material of Array.isArray(object.material)
        ? object.material
        : [object.material]) {
        if (material instanceof THREE.MeshStandardMaterial)
          materials.add(material)
      }
    })
    assert.isOk(materials.size > 0)
    for (const material of materials) {
      assert.isOk(
        material.roughness >= 0.85,
        'city surfaces must not produce sharp highlights',
      )
      assert.strictEqual(
        material.metalness,
        0,
        'exterior windows and trim must not act as mirrors',
      )
      assert.strictEqual(
        material.envMapIntensity,
        0,
        'environment reflections must not flash across the city',
      )
    }
  } finally {
    city.dispose()
  }
})

test('traffic moves and reaches the same positions at the same absolute time regardless of frame cadence or hidden sectors', () => {
  const frequent = new OfficeCity(bounds),
    single = new OfficeCity(bounds)
  try {
    frequent.update(0, center, true)
    const initial = trafficTransforms(frequent.root)
    for (let frame = 1; frame <= 144; frame++) {
      const angle = frame * 0.05
      frequent.update(
        (frame * 37.25) / 144,
        center
          .clone()
          .add(
            new THREE.Vector3(Math.sin(angle) * 50, 25, Math.cos(angle) * 50),
          ),
        false,
      )
    }
    frequent.update(37.25, center, true)
    single.update(37.25, center, true)
    const actual = trafficTransforms(frequent.root),
      expected = trafficTransforms(single.root)
    assert.isOk(actual.length > 0, 'the street must retain animated traffic')
    assert.strictEqual(actual.length, expected.length)
    assert.isOk(
      actual.some((value, index) => Math.abs(value - initial[index]) > 0.01),
      'cars must visibly travel',
    )
    actual.forEach((value, index) =>
      assert.isOk(
        Math.abs(value - expected[index]) < 1e-6,
        `traffic depends on update cadence at matrix component ${index}`,
      ),
    )
  } finally {
    frequent.dispose()
    single.dispose()
  }
})

test('city buildings remain outside the playable office at and above its floor', () => {
  for (const office of [
    bounds,
    { left: 1, back: 1, right: 9, front: 7 },
    { left: 1, back: 1, right: 40, front: 17 },
  ]) {
    const city = new OfficeCity(office)
    try {
      city.update(7, center, true)
      let elevatedMeshes = 0
      meshInstanceBounds(city.root, (box, object, instance) => {
        if (box.max.y < 0) return
        elevatedMeshes++
        const intersects =
          box.max.x > office.left + 1e-6 &&
          box.min.x < office.right - 1e-6 &&
          box.max.z > office.back + 1e-6 &&
          box.min.z < office.front - 1e-6
        assert.strictEqual(
          intersects,
          false,
          `${object.name} instance ${instance} occupies the office footprint`,
        )
      })
      assert.isOk(
        elevatedMeshes > 0,
        'neighboring buildings must reach the window view',
      )
    } finally {
      city.dispose()
    }
  }
})

test('removing a city disposes each shared geometry, material and instance buffer once', () => {
  const city = new OfficeCity(bounds)
  const resources = new Set<
    THREE.BufferGeometry | THREE.Material | THREE.InstancedMesh
  >()
  let meshes = 0
  city.root.traverse((object) => {
    if (!(object instanceof THREE.Mesh)) return
    meshes++
    resources.add(object.geometry)
    for (const material of Array.isArray(object.material)
      ? object.material
      : [object.material])
      resources.add(material)
    if (object instanceof THREE.InstancedMesh) resources.add(object)
  })
  assert.isOk(resources.size > 0 && meshes > 0)
  const counts = new Map([...resources].map((resource) => [resource, 0]))
  for (const resource of resources) {
    const counted = () => {
      counts.set(resource, counts.get(resource)! + 1)
    }
    if (resource instanceof THREE.InstancedMesh)
      resource.addEventListener('dispose', counted)
    else if (resource instanceof THREE.BufferGeometry)
      resource.addEventListener('dispose', counted)
    else resource.addEventListener('dispose', counted)
  }
  city.dispose()
  for (const [resource, count] of counts)
    assert.strictEqual(
      count,
      1,
      `${resource.type} resource was not released exactly once`,
    )
  assert.strictEqual(city.root.children.length, 0)
})
