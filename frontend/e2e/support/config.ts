/**
 * The ports the suite runs on, resolved the same way `playwright.config.ts` resolves them.
 *
 * Needed separately because worker-scoped fixtures build their own API context and cannot read the
 * test-scoped `baseURL` option.
 */
const previewPort = process.env.FLEET_E2E_PREVIEW_PORT ?? '4183'

export const baseURL = `http://127.0.0.1:${previewPort}`
