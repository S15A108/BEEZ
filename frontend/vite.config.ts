/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    tsconfigPaths: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: './src/test/setup.ts',
    // 22코어 PC에서 워커가 많이 뜨거나 다른 빌드와 겹치면 입력 흉내가 많은 화면 시험이
    // 단독(1~2초)의 서너 배로 느려져 기본 5초를 넘긴다. 실패 판정만 늦추고 통과 시간은 그대로다(D44).
    testTimeout: 15_000,
  },
})
