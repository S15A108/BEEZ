import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

// AI 에이전트(캔버스 SCR-06-rec-office-chars v485, 그림 코드 agent_cute.js). 비비처럼 머리가 큰(약 2.7등신) 흰 AI 로봇이고
// 포인트 색은 하늘색 하나다. 둥근 흰 헬멧·어두운 얼굴 화면·큰 눈·가슴 'AI' 배지·안테나 하나로 회원 '로봇'(흰 네모 몸,
// 청록 네모 얼굴)과 구분하고, 주황·노랑을 쓰지 않아 비비·벌 팀원과도 겹치지 않는다. 목 스카프와 왼팔 띠가 주인 색이다.
// 상태 넷은 안테나 불빛과 눈 색, 자세로 보인다: 연결됨 하늘색, 작업 중 노랑, 대기 회색 불빛·감은 눈·등 기대기,
// 연결 전 빨간 불빛·회색 몸·꺼진 화면·고개 떨굼·빨간 X.
// 단위는 m, 앞 = +z, 발바닥 y = 0, 안테나 공 끝 1.74. 사무실의 걷기·앉기 자세가 돌리도록 어깨·팔꿈치·엉덩이·무릎을
// 이름 붙인 축 그룹(arm-*, elbow-*, leg-*, knee-*)으로 두었다. 엉덩이는 바로 아래 자식이라 앉는 높이를 셀 수 있다.
export type AgentStatus = 'online' | 'working' | 'idle' | 'offline'

const STATUS: Record<AgentStatus, number> = {
  online: 0x5fc0ff,
  working: 0xffd54a,
  idle: 0x9a97a3,
  offline: 0xe5483f,
}
const EYE: Record<AgentStatus, string | null> = {
  online: '#8fd6ff',
  working: '#ffd54a',
  idle: '#c9cfd8',
  offline: null,
}
const ACCENT = 0x4aa3f0
const pm = (color: THREE.ColorRepresentation, extra = {}) =>
  new THREE.MeshPhysicalMaterial({
    color,
    roughness: 0.3,
    metalness: 0,
    clearcoat: 0.9,
    clearcoatRoughness: 0.15,
    ...extra,
  })
const mesh = (
  geometry: THREE.BufferGeometry,
  material: THREE.Material,
  x = 0,
  y = 0,
  z = 0,
) => {
  const object = new THREE.Mesh(geometry, material)
  object.position.set(x, y, z)
  object.castShadow = true
  object.receiveShadow = true
  return object
}
const group = (name: string, x = 0, y = 0, z = 0) => {
  const object = new THREE.Group()
  object.name = name
  object.position.set(x, y, z)
  return object
}
const patch = (
  r: number,
  phiC: number,
  phiL: number,
  thC: number,
  thL: number,
  seg = 48,
) =>
  new THREE.SphereGeometry(
    r,
    seg,
    seg,
    phiC - phiL / 2,
    phiL,
    thC - thL / 2,
    thL,
  )
function texture(
  w: number,
  h: number,
  draw: (g: CanvasRenderingContext2D) => void,
) {
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const g = canvas.getContext('2d')
  if (g) draw(g)
  const map = new THREE.CanvasTexture(canvas)
  map.colorSpace = THREE.SRGBColorSpace
  map.anisotropy = 8
  return map
}

// 어두운 화면 얼굴: 큰 눈, 눈 반짝임, 볼 터치, 작은 미소. 대기는 감은 눈, 연결 전은 꺼진 화면.
function faceTexture(status: AgentStatus) {
  const w = 1024
  const h = 640
  return texture(w, h, (g) => {
    g.save()
    g.beginPath()
    g.ellipse(w / 2, h * 0.5, w * 0.46, h * 0.44, 0, 0, Math.PI * 2)
    g.clip()
    const bg = g.createLinearGradient(0, 0, 0, h)
    bg.addColorStop(0, '#24304a')
    bg.addColorStop(1, '#111827')
    g.fillStyle = bg
    g.fillRect(0, 0, w, h)
    g.fillStyle = 'rgba(255,255,255,0.10)'
    g.beginPath()
    g.ellipse(w * 0.36, h * 0.2, w * 0.2, h * 0.06, -0.15, 0, Math.PI * 2)
    g.fill()
    const eye = EYE[status]
    if (eye) {
      for (const cx of [w * 0.33, w * 0.67]) {
        if (status === 'idle') {
          g.strokeStyle = eye
          g.lineWidth = 26
          g.lineCap = 'round'
          g.beginPath()
          g.arc(cx, h * 0.44, 62, Math.PI * 0.15, Math.PI * 0.85)
          g.stroke()
          continue
        }
        const glow = g.createRadialGradient(cx, h * 0.5, 10, cx, h * 0.47, 120)
        glow.addColorStop(0, '#ffffff')
        glow.addColorStop(0.35, eye)
        glow.addColorStop(1, eye)
        g.fillStyle = glow
        g.beginPath()
        g.ellipse(cx, h * 0.47, 92, 122, 0, 0, Math.PI * 2)
        g.fill()
        g.fillStyle = 'rgba(255,255,255,0.95)'
        g.beginPath()
        g.ellipse(cx - 30, h * 0.47 - 46, 30, 36, -0.3, 0, Math.PI * 2)
        g.fill()
        g.beginPath()
        g.ellipse(cx + 28, h * 0.47 + 44, 12, 13, 0, 0, Math.PI * 2)
        g.fill()
      }
      for (const cx of [w * 0.2, w * 0.8]) {
        const blush = g.createRadialGradient(cx, h * 0.7, 4, cx, h * 0.7, 70)
        blush.addColorStop(0, 'rgba(255,140,170,0.75)')
        blush.addColorStop(1, 'rgba(255,140,170,0)')
        g.fillStyle = blush
        g.beginPath()
        g.ellipse(cx, h * 0.7, 80, 46, 0, 0, Math.PI * 2)
        g.fill()
      }
      g.strokeStyle = eye
      g.lineWidth = 14
      g.lineCap = 'round'
      g.beginPath()
      g.arc(w / 2, h * 0.7, 34, Math.PI * 0.2, Math.PI * 0.8)
      g.stroke()
    }
    g.restore()
  })
}
const badgeTexture = (off: boolean) =>
  texture(256, 160, (g) => {
    g.fillStyle = off ? '#8d8894' : '#2f86d6'
    g.beginPath()
    g.roundRect(0, 0, 256, 160, 40)
    g.fill()
    g.fillStyle = '#ffffff'
    g.font = 'bold 104px Geist, "Malgun Gothic", sans-serif'
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    g.fillText('AI', 128, 86)
  })

export function buildAgent(owner: string, status: AgentStatus): THREE.Group {
  const off = status === 'offline'
  const light = STATUS[status]
  const limb = pm(off ? 0x8d8b93 : 0xa9b3c1, { roughness: 0.28, clearcoat: 1 })
  const shell = pm(off ? 0xd4d2d9 : 0xf8fafc)
  const accent = pm(off ? 0x9a97a3 : ACCENT, { clearcoat: 1 })
  const belly = pm(off ? 0xc4c1ca : 0xdcecfb)
  const mitt = pm(off ? 0xd9d7de : 0xfbfaff)
  const ownerColor = pm(off ? 0x8a8792 : owner, {
    roughness: 0.45,
    clearcoat: 0.4,
  })
  const root = group('avatar-agent')
  root.add(
    mesh(
      new THREE.SphereGeometry(0.17, 40, 28).scale(1.15, 0.7, 0.95),
      shell,
      0,
      0.53,
      0,
    ),
  )
  const spine = group('spine', 0, 0.54, 0)
  root.add(spine)
  // 흰 달걀 몸, 하늘색 배 판, 'AI' 배지, 상태 불빛
  const bodyScale = new THREE.Vector3(1.05, 0.95, 0.9)
  const torso = mesh(new THREE.SphereGeometry(0.23, 64, 48), shell, 0, 0.2, 0)
  const panel = mesh(
    patch(0.232, Math.PI / 2, 1.3, 1.9, 0.85),
    belly,
    0,
    0.2,
    0,
  )
  const badge = mesh(
    patch(0.234, Math.PI / 2 - 0.42, 0.42, 1.2, 0.26, 24),
    new THREE.MeshPhysicalMaterial({
      map: badgeTexture(off),
      roughness: 0.4,
      clearcoat: 0.6,
    }),
    0,
    0.2,
    0,
  )
  for (const part of [torso, panel, badge]) {
    part.scale.copy(bodyScale)
    spine.add(part)
  }
  spine.add(
    mesh(
      new THREE.SphereGeometry(0.018, 16, 12),
      new THREE.MeshStandardMaterial({
        color: light,
        emissive: light,
        emissiveIntensity: off ? 0.6 : 1.5,
      }),
      0.1,
      0.3,
      0.165,
    ),
  )
  // 목: 주인 색 스카프
  const neck = group('neck', 0, 0.4, 0)
  spine.add(neck)
  const scarf = mesh(new THREE.TorusGeometry(0.1, 0.035, 16, 48), ownerColor)
  scarf.rotation.x = Math.PI / 2
  const tail = mesh(
    new RoundedBoxGeometry(0.07, 0.13, 0.025, 3, 0.01),
    ownerColor,
    0.06,
    -0.06,
    0.12,
  )
  tail.rotation.z = 0.25
  neck.add(scarf, tail)
  // 머리: 위가 밝은 큰 헬멧, 얼굴 화면, 귀 원판, 상태 불빛 안테나
  const head = group('head', 0, 0.3, 0)
  neck.add(head)
  const headScale = new THREE.Vector3(1.07, 0.96, 1.0)
  const helmetShape = new THREE.SphereGeometry(0.31, 96, 64)
  const top = new THREE.Color(off ? '#c9c7cf' : '#ffffff')
  const bottom = new THREE.Color(off ? '#8f8c96' : '#cfd6e0')
  const colors: number[] = []
  const position = helmetShape.attributes.position
  for (let i = 0; i < position.count; i++) {
    const k = (position.getY(i) / 0.31 + 1) / 2
    const color = bottom.clone().lerp(top, Math.pow(k, 0.9))
    colors.push(color.r, color.g, color.b)
  }
  helmetShape.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3))
  const helmet = mesh(
    helmetShape,
    pm(0xffffff, { vertexColors: true, roughness: 0.25, clearcoat: 1 }),
  )
  helmet.scale.copy(headScale)
  const faceMap = faceTexture(status)
  const face = mesh(
    patch(0.313, Math.PI / 2, 2.0, 1.66, 1.18, 64),
    new THREE.MeshPhysicalMaterial({
      map: faceMap,
      transparent: true,
      alphaTest: 0.5,
      roughness: 0.08,
      clearcoat: 1,
      emissive: 0xffffff,
      emissiveMap: faceMap,
      emissiveIntensity: off ? 0 : 0.9,
    }),
  )
  face.scale.copy(headScale)
  head.add(helmet, face)
  for (const side of [-1, 1]) {
    const ear = group('ear', side * 0.328, -0.01, 0)
    ear.rotation.z = Math.PI / 2
    const disc = mesh(new THREE.SphereGeometry(0.1, 32, 16), shell)
    disc.scale.set(1, 0.4, 1)
    const ring = mesh(new THREE.TorusGeometry(0.068, 0.016, 12, 48), accent)
    ring.rotation.x = Math.PI / 2
    ring.position.y = side * 0.035
    ear.add(disc, ring)
    head.add(ear)
  }
  head.add(
    mesh(new THREE.SphereGeometry(0.045, 24, 16), accent, 0, 0.3, 0),
    mesh(new THREE.CylinderGeometry(0.013, 0.013, 0.12, 12), limb, 0, 0.37, 0),
    mesh(
      new THREE.SphereGeometry(0.05, 24, 16),
      new THREE.MeshStandardMaterial({
        color: light,
        emissive: light,
        emissiveIntensity: off ? 0.6 : 1.4,
      }),
      0,
      0.45,
      0,
    ),
  )
  // 짧은 팔과 흰 벙어리장갑. 왼팔(+x)에 주인 색 띠
  for (const side of [1, -1]) {
    const name = side === 1 ? 'left' : 'right'
    const shoulder = group(`arm-${name}`, side * 0.24, 0.32, 0)
    shoulder.rotation.z = side * 0.14
    shoulder.add(
      mesh(new THREE.SphereGeometry(0.068, 24, 16), limb),
      mesh(new THREE.CapsuleGeometry(0.062, 0.07, 8, 16), limb, 0, -0.08, 0),
    )
    if (side === 1)
      shoulder.add(
        mesh(
          new THREE.CylinderGeometry(0.068, 0.068, 0.045, 24),
          ownerColor,
          0,
          -0.08,
          0,
        ),
      )
    const elbow = group(`elbow-${name}`, 0, -0.16, 0)
    elbow.rotation.x = -0.2
    elbow.add(
      mesh(new THREE.CapsuleGeometry(0.057, 0.06, 8, 16), limb, 0, -0.07, 0),
    )
    const hand = group('hand', 0, -0.155, 0)
    hand.add(
      mesh(new THREE.SphereGeometry(0.078, 28, 20).scale(1, 1.05, 0.85), mitt),
      mesh(
        new THREE.SphereGeometry(0.032, 16, 12),
        mitt,
        side * -0.058,
        0.02,
        0.04,
      ),
    )
    elbow.add(hand)
    shoulder.add(elbow)
    spine.add(shoulder)
  }
  // 다리: 엉덩이 축은 루트 바로 아래(골반 높이 0.48)
  for (const side of [1, -1]) {
    const name = side === 1 ? 'left' : 'right'
    const hip = group(`leg-${name}`, side * 0.1, 0.48, 0)
    hip.add(
      mesh(new THREE.CapsuleGeometry(0.085, 0.07, 8, 16), limb, 0, -0.1, 0),
    )
    const knee = group(`knee-${name}`, 0, -0.2, 0)
    knee.add(
      mesh(new THREE.CapsuleGeometry(0.08, 0.06, 8, 16), limb, 0, -0.1, 0),
    )
    const foot = mesh(
      new THREE.SphereGeometry(0.1, 28, 20),
      limb,
      0,
      -0.22,
      0.045,
    )
    foot.scale.set(1, 0.58, 1.35)
    knee.add(foot)
    hip.add(knee)
    root.add(hip)
  }
  head.rotation.z = 0.06
  if (status === 'idle') {
    // 등을 기대고 고개를 살짝 기울인다.
    spine.rotation.x = -0.16
    head.rotation.z = 0.12
  }
  if (off) {
    // 고개를 떨구고, 머리 위에 빨간 X(바로 서게 기울임을 되돌린다)
    spine.rotation.x = 0.3
    neck.rotation.x = 0.55
    const mark = group('offline-mark', 0, 0.68, 0)
    mark.rotation.x = -0.85
    const red = new THREE.MeshStandardMaterial({
      color: 0xd9312b,
      emissive: 0xd9312b,
      emissiveIntensity: 0.4,
    })
    for (const turn of [0.8, -0.8]) {
      const bar = mesh(new RoundedBoxGeometry(0.16, 0.04, 0.04, 3, 0.012), red)
      bar.rotation.z = turn
      mark.add(bar)
    }
    head.add(mark)
  }
  return root
}
