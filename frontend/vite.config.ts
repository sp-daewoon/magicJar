/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/__tests__/setup.ts'],
    // 10-core 호스트에서 기본 worker 수는 full-suite React 테스트의 5초 timeout flake를 유발했다.
    // timeout 완화 대신 worker 상한을 고정해 테스트 의미를 유지하고 자원 경쟁만 제거한다.
    maxWorkers: 4,
    // qa Visual (Playwright) 디렉토리는 vitest 수집 대상에서 제외 — @playwright/test API 충돌 회피
    exclude: ['node_modules', 'dist', 'tests/e2e/**', 'playwright-report/**', 'test-results/**'],
  },
  server: {
    port: 5173,
    proxy: {
      // dev 환경에서 /api 요청을 api-app(8080)으로 프록시
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // STOMP WebSocket 프록시 (PR-6+에서 활성화)
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 5173,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        // 청크 분리 — LWC + 라우터는 별도 청크로 분리해 초기 로드 최소화
        manualChunks: {
          'vendor-react': ['react', 'react-dom'],
          'vendor-router': ['react-router-dom'],
          'vendor-query': ['@tanstack/react-query'],
          'vendor-chart': ['lightweight-charts'],
        },
      },
    },
  },
})
