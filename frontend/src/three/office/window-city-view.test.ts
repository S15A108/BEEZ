import * as THREE from 'three'
import { assert, test } from 'vitest'
import { WindowCityView } from './window-city-view'

test('나중에 끼운 도시 건물도 3인칭에서는 유리창 안으로만 보인다', () => {
  const view = new WindowCityView()
  const root = new THREE.Group()
  const early = new THREE.MeshStandardMaterial()
  root.add(new THREE.Mesh(new THREE.BoxGeometry(), early))
  view.setCityStencil(root, true)
  assert.strictEqual(early.stencilWrite, true)

  // 모델을 받은 뒤 건물을 끼우고 내용 버전을 올린다(OfficeCity.useKenney).
  const late = new THREE.MeshStandardMaterial()
  root.add(new THREE.Mesh(new THREE.BoxGeometry(), late))
  root.userData.cityVersion = 1
  view.setCityStencil(root, true)
  assert.strictEqual(late.stencilWrite, true)
  assert.strictEqual(late.stencilFunc, THREE.EqualStencilFunc)

  // 1인칭에서는 모두 풀린다.
  view.setCityStencil(root, false)
  assert.strictEqual(early.stencilWrite, false)
  assert.strictEqual(late.stencilWrite, false)
  view.dispose()
})
