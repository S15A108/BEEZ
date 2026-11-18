---
name: three-scene
description: frontend에 three.js / React Three Fiber 3D 씬, 3D 모델(glTF/GLB), 3D 인터랙션을 추가하거나 고칠 때 사용. 파일 위치, lazy loading, 모델 에셋 배치, 프레임 루프 성능 규칙과 예제 코드를 담는다.
---

# three-scene

규칙 요약은 `frontend/README.md`의 "3D" 섹션에 있다. 이 스킬은 그 규칙대로 씬을 추가하는 절차다.

## 1. 씬 컴포넌트 만들기

`src/three/<이름>Scene.tsx`에 `<Canvas>`를 포함한 컴포넌트를 만들고 default export한다.

```tsx
import { Canvas, useFrame } from '@react-three/fiber'
import { useRef } from 'react'
import type { Mesh } from 'three'

function SpinningBox() {
  const ref = useRef<Mesh>(null)
  useFrame((_, delta) => {
    if (ref.current) ref.current.rotation.y += delta
  })
  return (
    <mesh ref={ref}>
      <boxGeometry />
      <meshStandardMaterial color="orange" />
    </mesh>
  )
}

export default function HeroScene() {
  return (
    <Canvas camera={{ position: [0, 0, 3] }}>
      <ambientLight intensity={0.5} />
      <directionalLight position={[2, 2, 2]} />
      <SpinningBox />
    </Canvas>
  )
}
```

## 2. lazy로 불러오기

`<Canvas>`가 들어간 컴포넌트는 정적 import하지 않는다. 정적 import하면 three.js 전체가 첫 화면 번들에 들어간다.

```tsx
import { lazy, Suspense } from 'react'

const HeroScene = lazy(() => import('@/three/HeroScene'))

export default function MainPage() {
  return (
    <Suspense fallback={<div>3D 로딩 중</div>}>
      <HeroScene />
    </Suspense>
  )
}
```

## 3. 모델(GLB) 추가

- 파일은 `public/models/`에 두고 `/models/<파일>.glb` URL로 로드한다. Vite는 `.glb`를 import 대상으로 인식하지 않는다.
- 커밋 전에 파일 크기를 확인한다. 10MB를 넘으면 진행을 멈추고 사용자에게 알린다.
- 압축하지 않은 모델은 추가 패키지 없이 `useLoader`로 로드한다. 로딩 중에는 가장 가까운 `<Suspense>`의 fallback이 보인다.

```tsx
import { useLoader } from '@react-three/fiber'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'

export function Robot() {
  const gltf = useLoader(GLTFLoader, '/models/robot.glb')
  return <primitive object={gltf.scene} />
}
```

- Draco나 Meshopt로 압축한 모델, 카메라 컨트롤(OrbitControls) 같은 헬퍼가 필요하면 `@react-three/drei` 설치를 사용자에게 먼저 묻는다. 설치 후에는 `useGLTF`가 압축 디코더를 자동으로 붙인다. 이때 Draco 디코더는 기본적으로 외부 CDN에서 받는다.
- 같은 모델을 여러 개 배치할 때는 `useLoader`가 캐시한 결과를 `gltf.scene.clone()`으로 복제한다. 같은 객체를 `<primitive>` 두 곳에 넣으면 하나만 보인다.

## 4. 성능 규칙

- 매 프레임 바뀌는 값은 `useFrame` 안에서 ref로 직접 바꾼다. 프레임마다 `setState`를 호출하지 않는다.
- 움직임이 없는 씬은 `<Canvas frameloop="demand">`로 바꾼다.
- JSX로 만든 geometry와 material은 R3F가 언마운트 시 정리한다. effect 안에서 `new`로 직접 만든 three 객체는 cleanup에서 `.dispose()`를 호출한다.

## 5. 검증

`fe-verify` 스킬로 lint와 build를 확인한다. 브라우저에서 화면이 렌더링되는지, 콘솔에 WebGL 에러가 없는지도 확인한다.
