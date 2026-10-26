import { afterEach, expect, it, vi } from 'vitest'
import type * as THREE from 'three'
import { CityRenderer3D } from './city/CityRenderer3D'
import { OfficeRenderer3D } from './office/OfficeRenderer3D'
import { OutdoorStreetRenderer } from './office/OutdoorStreetRenderer'

afterEach(() => vi.restoreAllMocks())

it('R3F의 렌더러와 캔버스는 장면을 정리해도 유지한다', () => {
  const canvas = document.createElement('canvas')
  const host = document.createElement('div')
  host.appendChild(canvas)
  const context = {
    createRadialGradient: () => ({ addColorStop: vi.fn() }),
    fillRect: vi.fn(),
    fillText: vi.fn(),
    measureText: () => ({ width: 100 }),
  } as unknown as CanvasRenderingContext2D
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context)
  const external = {
    domElement: canvas,
    shadowMap: { enabled: false, type: 0 },
    setPixelRatio: vi.fn(),
    setClearColor: vi.fn(),
    setSize: vi.fn(),
    dispose: vi.fn(),
    forceContextLoss: vi.fn(),
  }
  const gl = external as unknown as THREE.WebGLRenderer
  const engines = [
    new CityRenderer3D(host, true, gl),
    new OfficeRenderer3D(host, true, true, gl),
    new OutdoorStreetRenderer(
      host,
      { left: 1, back: 1, right: 29, front: 16 },
      '팀 사무실',
      gl,
    ),
  ]
  for (const engine of engines) {
    engine.setSize(800, 600)
    engine.dispose()
  }
  expect(external.dispose).not.toHaveBeenCalled()
  expect(external.forceContextLoss).not.toHaveBeenCalled()
  expect(external.setSize).not.toHaveBeenCalled()
  expect(external.setPixelRatio).not.toHaveBeenCalled()
  expect(canvas.parentNode).toBe(host)
})
