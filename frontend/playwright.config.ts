import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.BEEZ_E2E_PORT ?? 5173)

export default defineConfig({
  testDir: './e2e',
  // 3D 사무실은 프레임이 무거워 한 번에 한 브라우저만 띄운다(D25).
  workers: 1,
  use: {
    baseURL: `http://127.0.0.1:${port}`,
  },
  projects: [
    {
      name: 'chromium',
      // headless shell은 WebGL을 CPU(SwiftShader)로 그려 프레임이 1초를 넘는다. 새 headless는 GPU를 쓴다(D25).
      use: { ...devices['Desktop Chrome'], channel: 'chromium' },
    },
  ],
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: !process.env.CI,
    // E2E는 개수에 기대는 흐름이 있어 기본 예시로 돈다. 로컬 개발 서버는 우리 팀 협업 서비스 예시를 넉넉히 넣는다(mocks/state.ts).
    env: { VITE_MOCK_SEED: 'basic' },
  },
})
