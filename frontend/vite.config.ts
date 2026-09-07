/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * The API the dev and preview servers proxy to.
 *
 * Parameterised for one reason only: the browser suite runs the backend on its own isolated port so
 * it can never reach — or be reached by — a developer's `make dev` server. `vite preview` inherits
 * `server.proxy`, so both servers follow this single target and there is no second copy to drift.
 */
const apiTarget = process.env.FLEET_API_PROXY_TARGET ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': apiTarget },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
    // Component tests only. Without this, Vitest's default glob would also collect `e2e/*.spec.ts`
    // and run Playwright journeys under jsdom, where they fail for reasons that mean nothing.
    include: ['src/**/*.{test,spec}.{ts,tsx,js}'],
  },
})
