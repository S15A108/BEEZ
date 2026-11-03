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
  },
})
