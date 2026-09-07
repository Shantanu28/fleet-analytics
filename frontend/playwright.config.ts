/**
 * The browser suite: the production frontend build, the real API and the seeded database.
 *
 * The servers are deliberately *not* started from here. `scripts/e2e.mjs` owns the whole lifecycle,
 * because Playwright exits on SIGTERM without stopping its `webServer` children — an interrupted
 * run would leave a JVM and a preview server holding their ports. One owner, one cleanup path.
 * Run this suite through `make e2e`; running `npx playwright test` directly expects those servers
 * to be up already, on the ports below.
 *
 * Only the custom reporter's allowlisted summary is published. Raw assertion errors and automatic
 * error-context snapshots can contain restricted data even with tracing off. They stay local in
 * test-results and must not be uploaded. Screenshots, traces and video are disabled.
 */
import { defineConfig, devices } from '@playwright/test'

const previewPort = Number(process.env.FLEET_E2E_PREVIEW_PORT ?? 4183)

export default defineConfig({
  testDir: './e2e',
  // One worker against one shared seeded database: the journeys read the same dataset, and several
  // browsers driving it concurrently would only add scheduling noise to no benefit.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  // A previous cold request took about eleven seconds. This is a functional-test timeout, not a
  // latency target; attributing the delay to JIT/cache requires separate profiling.
  expect: { timeout: 30_000 },
  reporter: [['../scripts/playwright-reporter.mjs']],
  outputDir: './test-results',
  use: {
    baseURL: `http://127.0.0.1:${previewPort}`,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
