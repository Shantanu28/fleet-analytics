/**
 * Targeted accessibility checks: keyboard order, visible focus, and the charts' text alternative.
 *
 * These are three specific, checkable properties — not a WCAG conformance audit. Passing them says
 * the filter controls can be reached and identified by keyboard, that focus is visible when they
 * are, and that every plotted series is also readable as a table. It says nothing about contrast,
 * reflow at every zoom level, or the many other criteria a real audit covers.
 */
import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import { dashboardSections, filterControls, openDashboard } from './support/app'
import { NORTHSTAR_ADMIN } from './support/users'

/**
 * The accessible name of whatever currently has focus.
 *
 * Read from the DOM rather than guessed: a control's name comes from its `<label for>`, its
 * `aria-label` or its own text, which is exactly what a screen reader would announce.
 */
async function focusedName(page: Page): Promise<string> {
  return page.evaluate(() => {
    const active = document.activeElement as HTMLElement | null
    if (active === null || active === document.body) return ''
    const id = active.getAttribute('id')
    const label = id === null ? null : document.querySelector(`label[for="${CSS.escape(id)}"]`)
    const name = label?.textContent ?? active.getAttribute('aria-label') ?? active.textContent ?? ''
    return name.trim()
  })
}

/** Every accessible name Tab lands on, in order, until `last` is reached or `limit` is spent. */
async function tabOrder(page: Page, last: string, limit = 40): Promise<string[]> {
  const stops: string[] = []
  await page.locator('body').press('Tab')
  for (let step = 0; step < limit; step += 1) {
    const name = await focusedName(page)
    stops.push(name)
    if (name === last) break
    await page.keyboard.press('Tab')
  }
  return stops
}

/** True when `expected` appears within `stops` in that order, ignoring anything between. */
function containsInOrder(stops: readonly string[], expected: readonly string[]): boolean {
  let index = 0
  for (const stop of stops) if (stop === expected[index]) index += 1
  return index === expected.length
}

test('the filter controls are reached by keyboard in order, each with its visible label', async ({
  page,
  scopes,
}) => {
  const controls = filterControls(page)

  // The headline and context requests are independent. Exercise the ordering that exposed the
  // CI race: cards can be visible while the date presets still await their reporting cutoff.
  let releaseContext!: () => void
  const contextGate = new Promise<void>((resolve) => { releaseContext = resolve })
  await page.route('**/api/v1/analytics/context', async (route) => {
    const response = await route.fetch()
    await contextGate
    await route.fulfill({ response })
  })
  try {
    await openDashboard(page, NORTHSTAR_ADMIN)
    await expect(controls.preset(30)).toBeDisabled()
  } finally {
    releaseContext()
  }

  // Wait for the controls we actually test, not just the independently loaded KPI section.
  for (const days of [7, 30, 90]) await expect(controls.preset(days)).toBeEnabled()
  await expect(controls.from).toHaveValue(scopes.defaultFrom)
  await expect(controls.to).toHaveValue(scopes.defaultTo)

  // Signing out comes first: it is the first focusable control on the page, and it must be
  // reachable without waiting on any request having succeeded.
  //
  // A subsequence, not an exact list. Chromium gives a `type="date"` input several internal tab
  // stops of its own — day, month, year — and pinning the count would be asserting on the browser's
  // date widget rather than on this page's order. Every stop still carries the input's own label.
  const stops = await tabOrder(page, 'Reset filters')
  expect(
    containsInOrder(stops, [
      'Sign out',
      '7 days',
      '30 days',
      '90 days',
      'From',
      'To',
      'Team',
      'Repository',
      'Reset filters',
    ]),
    `the keyboard order was: ${stops.join(' → ')}`,
  ).toBe(true)

  // Nothing focusable along the way is anonymous.
  expect(stops.filter((stop) => stop === '')).toEqual([])

  // Apply is skipped while there is nothing to apply, and joins the order once a date is edited.
  await expect(controls.apply).toBeDisabled()
  await controls.from.fill(scopes.budgetMonth.from)
  await expect(controls.apply).toBeEnabled()
  await controls.to.focus()
  for (let step = 0; step < 8; step += 1) {
    if ((await focusedName(page)) === 'Apply') break
    await page.keyboard.press('Tab')
  }
  await expect(controls.apply).toBeFocused()
})

test('a keyboard-focused control shows a visible focus outline', async ({ page }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)

  await page.locator('body').press('Tab')
  const outline = await page.evaluate(() => {
    const active = document.activeElement
    if (active === null) return null
    const style = getComputedStyle(active)
    return { style: style.outlineStyle, width: style.outlineWidth, tag: active.tagName }
  })

  expect(outline).not.toBeNull()
  expect(outline!.style).not.toBe('none')
  expect(Number.parseFloat(outline!.width)).toBeGreaterThan(0)
})

test('every plotted series is also readable as a table, zero days included', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const trends = dashboardSections(page).trends

  const spend = trends.locator('summary').filter({ hasText: 'total agent spend per day' })
  await spend.click()

  const rows = trends.getByRole('row').filter({ has: page.getByRole('rowheader') })
  // One row per complete UTC day in the selected range: a day with no activity is a zero, not a
  // gap, so the table is as long as the range itself.
  const days =
    (Date.parse(`${scopes.defaultTo}T00:00:00Z`) - Date.parse(`${scopes.defaultFrom}T00:00:00Z`)) /
      86_400_000 +
    1
  await expect(rows).toHaveCount(days)
  await expect(rows.first()).toContainText(scopes.defaultFrom)
  await expect(rows.last()).toContainText(scopes.defaultTo)
})
