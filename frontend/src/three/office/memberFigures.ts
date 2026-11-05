import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

// 회원 캐릭터 로봇·벌(캔버스 SCR-05-v2-account v440). 보드 그림을 만든 캔버스 코드를 그대로 옮기고, 사무실에서 걷고 앉을 수
// 있게 팔·다리만 어깨·엉덩이를 축으로 하는 그룹(arm-*, leg-*)으로 묶었다. 서 있을 때 모양은 보드와 같다.
// 단위는 보드 단위(발바닥 y = 0, 앞 = +z). 키 × 폭: 로봇 1.215 × 0.72, 벌 1.195 × 0.657. 사람(Kenney)과 같은 키로 맞춰 쓴다.
// 비비(AI 팀장 마스코트)와는 다른 캐릭터다.

const mat = (color: number, extra: THREE.MeshStandardMaterialParameters = {}) =>
  new THREE.MeshStandardMaterial({
    color,
    roughness: 0.85,
    metalness: 0,
    ...extra,
  })
const rbox = (
  w: number,
  h: number,
  d: number,
  r: number,
  material: THREE.Material,
) => new THREE.Mesh(new RoundedBoxGeometry(w, h, d, 4, r), material)
const at = <T extends THREE.Object3D>(
  object: T,
  x: number,
  y: number,
  z: number,
) => {
  object.position.set(x, y, z)
  return object
}
// 축 그룹: 이름을 붙이고 안의 부품은 축 기준 위치로 넣는다.
const joint = (
  name: string,
  x: number,
  y: number,
  z: number,
  ...parts: THREE.Object3D[]
) => {
  const group = at(new THREE.Group(), x, y, z)
  group.name = name
  for (const part of parts) {
    part.position.x -= x
    part.position.y -= y
    part.position.z -= z
    group.add(part)
  }
  return group
}

// 로봇: 흰 몸, 청록 손발·귀 볼트, 짙은 화면 얼굴에 빛나는 눈·입, 주황 안테나 공
export function buildRobot(): THREE.Group {
  const shell = mat(0xe4e8ee)
  const trim = mat(0x3aa6a0)
  const dark = mat(0x23303a, { roughness: 0.4 })
  const glow = mat(0x7ff3e4, { emissive: 0x3fd9c6, emissiveIntensity: 0.9 })
  const g = new THREE.Group()
  g.name = 'avatar-robot'
  for (const [name, x] of [
    ['leg-right', -0.09],
    ['leg-left', 0.09],
  ] as const)
    g.add(
      joint(
        name,
        x,
        0.2,
        0,
        at(rbox(0.3, 0.1, 0.22, 0.03, trim), x, 0.05, 0), // 발
        at(rbox(0.11, 0.12, 0.13, 0.03, shell), x, 0.14, 0), // 다리
      ),
    )
  g.add(at(rbox(0.4, 0.3, 0.28, 0.06, shell), 0, 0.34, 0)) // 몸
  g.add(at(rbox(0.22, 0.12, 0.02, 0.02, trim), 0, 0.36, 0.14)) // 가슴판
  g.add(at(rbox(0.05, 0.05, 0.02, 0.012, glow), -0.05, 0.36, 0.152))
  g.add(at(rbox(0.05, 0.05, 0.02, 0.012, mat(0xf3c530)), 0.05, 0.36, 0.152))
  for (const [name, x] of [
    ['arm-right', -0.25],
    ['arm-left', 0.25],
  ] as const)
    g.add(
      joint(
        name,
        x,
        0.44,
        0,
        at(rbox(0.09, 0.22, 0.12, 0.04, shell), x, 0.33, 0), // 팔
        at(rbox(0.11, 0.09, 0.13, 0.035, trim), x, 0.2, 0), // 손
      ),
    )
  g.add(at(rbox(0.1, 0.06, 0.1, 0.02, trim), 0, 0.51, 0)) // 목
  const head = at(rbox(0.62, 0.5, 0.5, 0.1, shell), 0, 0.78, 0) // 머리
  head.name = 'face-box'
  g.add(head)
  g.add(at(rbox(0.5, 0.3, 0.04, 0.06, dark), 0, 0.78, 0.245)) // 얼굴 화면
  g.add(at(rbox(0.09, 0.11, 0.02, 0.03, glow), -0.11, 0.8, 0.27)) // 눈
  g.add(at(rbox(0.09, 0.11, 0.02, 0.03, glow), 0.11, 0.8, 0.27))
  g.add(at(rbox(0.16, 0.03, 0.02, 0.012, glow), 0, 0.69, 0.27)) // 입
  g.add(at(rbox(0.06, 0.16, 0.16, 0.025, trim), -0.33, 0.78, 0)) // 귀 볼트
  g.add(at(rbox(0.06, 0.16, 0.16, 0.025, trim), 0.33, 0.78, 0))
  g.add(
    at(
      new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, 0.14, 12), dark),
      0,
      1.09,
      0,
    ),
  ) // 안테나
  g.add(
    at(
      new THREE.Mesh(new THREE.SphereGeometry(0.045, 20, 14), mat(0xff7a59)),
      0,
      1.17,
      0,
    ),
  )
  return g
}

// 벌: 털 난 노란 몸에 짙은 줄무늬 두 줄, 반투명 날개, 동그란 머리에 눈·볼·웃는 입, 더듬이
export function buildBee(): THREE.Group {
  const fur = mat(0xf7c948)
  const stripe = mat(0x3a2f22)
  const wing = mat(0xffffff, {
    transparent: true,
    opacity: 0.55,
    roughness: 0.2,
  })
  const g = new THREE.Group()
  g.name = 'avatar-bee'
  for (const [name, x] of [
    ['leg-right', -0.09],
    ['leg-left', 0.09],
  ] as const)
    g.add(
      joint(
        name,
        x,
        0.09,
        0.01,
        at(rbox(0.12, 0.09, 0.16, 0.035, stripe), x, 0.045, 0.01), // 발
      ),
    )
  g.add(at(rbox(0.42, 0.4, 0.34, 0.12, fur), 0, 0.29, 0)) // 둥근 몸
  g.add(at(rbox(0.43, 0.07, 0.35, 0.03, stripe), 0, 0.22, 0)) // 줄무늬
  g.add(at(rbox(0.43, 0.07, 0.35, 0.03, stripe), 0, 0.36, 0))
  for (const [name, x] of [
    ['arm-right', -0.25],
    ['arm-left', 0.25],
  ] as const)
    g.add(
      joint(
        name,
        x,
        0.38,
        0.02,
        at(rbox(0.08, 0.16, 0.1, 0.035, fur), x, 0.3, 0.02), // 팔
      ),
    )
  for (const side of [-1, 1]) {
    // 날개(등 뒤로 벌어짐)
    const blade = rbox(0.3, 0.2, 0.02, 0.08, wing)
    blade.name = side < 0 ? 'wing-left' : 'wing-right'
    blade.position.set(side * 0.17, 0.46, -0.2)
    blade.rotation.set(0.3, side * 0.5, side * 0.35)
    g.add(blade)
  }
  const head = at(rbox(0.6, 0.5, 0.48, 0.18, fur), 0, 0.73, 0) // 머리
  head.name = 'face-box'
  g.add(head)
  g.add(at(rbox(0.07, 0.11, 0.02, 0.03, stripe), -0.12, 0.76, 0.24)) // 눈
  g.add(at(rbox(0.07, 0.11, 0.02, 0.03, stripe), 0.12, 0.76, 0.24))
  const shine = mat(0xffffff)
  g.add(at(rbox(0.025, 0.035, 0.01, 0.01, shine), -0.105, 0.79, 0.252)) // 눈 반짝임
  g.add(at(rbox(0.025, 0.035, 0.01, 0.01, shine), 0.135, 0.79, 0.252))
  const cheek = mat(0xff8f8f)
  g.add(at(rbox(0.08, 0.04, 0.02, 0.015, cheek), -0.21, 0.68, 0.24)) // 볼
  g.add(at(rbox(0.08, 0.04, 0.02, 0.015, cheek), 0.21, 0.68, 0.24))
  g.add(at(rbox(0.09, 0.025, 0.02, 0.01, stripe), 0, 0.665, 0.245)) // 웃는 입
  for (const side of [-1, 1]) {
    // 더듬이
    const antenna = new THREE.Mesh(
      new THREE.CylinderGeometry(0.014, 0.014, 0.2, 10),
      stripe,
    )
    antenna.position.set(side * 0.12, 1.06, 0.02)
    antenna.rotation.z = -side * 0.35
    g.add(antenna)
    g.add(
      at(
        new THREE.Mesh(new THREE.SphereGeometry(0.04, 18, 12), stripe),
        side * 0.155,
        1.155,
        0.02,
      ),
    )
  }
  return g
}

/** 템플릿이 가진 geometry·material을 정리한다(장면의 복제본은 같은 것을 나눠 쓴다). */
export function disposeFigure(figure: THREE.Object3D) {
  figure.traverse((object) => {
    const mesh = object as THREE.Mesh
    if (!mesh.isMesh) return
    mesh.geometry.dispose()
    const materials = Array.isArray(mesh.material)
      ? mesh.material
      : [mesh.material]
    materials.forEach((material) => material.dispose())
  })
}
