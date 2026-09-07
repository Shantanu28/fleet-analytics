/**
 * The suite's own `test`, carrying the seeded identities every journey needs.
 *
 * `scopes` is worker-scoped: resolving it costs a handful of API reads, the dataset is read-only,
 * and one worker runs the whole suite — so it is resolved once and shared. It is deliberately not a
 * module-level constant, because that would run on import, before Playwright has started the
 * servers the reads depend on.
 */
import { test as base } from '@playwright/test'
import { baseURL } from './config'
import { resolveScopes, type Scopes } from './api'

// No test-scoped fixtures are added, only the worker-scoped `scopes`.
export const test = base.extend<object, { scopes: Scopes }>({
  scopes: [
    async ({ playwright }, use) => {
      const request = await playwright.request.newContext({ baseURL })
      try {
        await use(await resolveScopes(request))
      } finally {
        await request.dispose()
      }
    },
    { scope: 'worker' },
  ],
})

export { expect } from '@playwright/test'
