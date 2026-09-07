/**
 * How the journeys drive the application.
 *
 * Every locator here is a role, label or accessible name — what a person or a screen reader would
 * use — never a CSS class. A journey that reaches for `.kpi-card__label` proves the markup has not
 * changed; a journey that reaches for the heading "Merged agent PRs" proves the product still
 * presents that measure.
 *
 * There are no fixed waits anywhere in this suite. Playwright's assertions retry, and where a test
 * needs to know that a request actually happened, the wait is registered *before* the action that
 * triggers it — otherwise the response can land first and the wait never resolves.
 */
import { expect, type Locator, type Page, type Response } from '@playwright/test'
import type { DemoUser } from './users'

const DASHBOARD_PATH = '/api/v1/analytics/dashboard'

/** A selection as it appears in the URL, in the application's own canonical parameter order. */
export type Selection = {
  from?: string
  to?: string
  teamId?: string
  repositoryId?: string
  grouping?: string
}

const PARAMETER_ORDER = ['from', 'to', 'teamId', 'repositoryId', 'grouping'] as const

export function searchString(selection: Selection): string {
  const parameters = new URLSearchParams()
  for (const name of PARAMETER_ORDER) {
    const value = selection[name]
    if (value !== undefined) parameters.set(name, value)
  }
  const query = parameters.toString()
  return query === '' ? '' : `?${query}`
}

/** The applied selection as the browser's own address bar reports it. */
export function appliedSelection(page: Page): Record<string, string> {
  const parameters = new URLSearchParams(new URL(page.url()).search)
  return Object.fromEntries(parameters.entries())
}

/**
 * Waits for the next dashboard response.
 *
 * Registered before the triggering action, and awaited after it. The page issues a dashboard
 * request whenever the applied selection changes, so this is how a test distinguishes "the label
 * changed" from "the server was actually asked a different question".
 */
export function dashboardResponse(page: Page): Promise<Response> {
  return page.waitForResponse((response) => response.url().includes(DASHBOARD_PATH))
}

/** The query parameters of a dashboard request, so a test can assert what was actually asked. */
export function requestedSelection(response: Response): Record<string, string> {
  return Object.fromEntries(new URL(response.url()).searchParams.entries())
}

export async function signIn(page: Page, user: DemoUser): Promise<void> {
  await page.getByLabel('Username', { exact: true }).fill(user.username)
  await page.getByLabel('Password', { exact: true }).fill(user.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
}

/**
 * Signs in at the given selection, which the URL keeps across the sign-in.
 *
 * Always from a cold start, because the token is held in memory: a `page.goto` after signing in
 * would silently end the session. Changing the selection mid-session is done through the controls,
 * or through Back and Forward — never by navigating.
 */
export async function openSelection(
  page: Page,
  user: DemoUser,
  selection: Selection = {},
): Promise<void> {
  await page.goto(`/${searchString(selection)}`)
  await signIn(page, user)
}

/** {@link openSelection}, for the ordinary case where the selection does produce figures. */
export async function openDashboard(
  page: Page,
  user: DemoUser,
  selection: Selection = {},
): Promise<void> {
  await openSelection(page, user, selection)
  await expect(dashboardSections(page).kpis).toBeVisible()
}

export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
}

/** The page's landmarks, named the way the product names them. */
export function dashboardSections(page: Page) {
  return {
    kpis: page.getByRole('region', { name: 'Headline measures' }),
    filters: page.getByRole('region', { name: 'Filters' }),
    funnel: page.getByRole('region', { name: /^Outcome funnel/ }),
    trends: page.getByRole('region', { name: 'Daily trends' }),
    attention: page.getByRole('region', { name: 'Needs attention' }),
    comparison: page.getByRole('region', { name: /^Comparison — / }),
  }
}

export function filterControls(page: Page) {
  const filters = dashboardSections(page).filters
  return {
    // `exact` throughout: label matching is substring and case-insensitive by default, so a bare
    // "To" also matches "Reposi(to)ry" and a bare "Team" would match any label containing it.
    preset: (days: number) => filters.getByRole('button', { name: `${days} days`, exact: true }),
    from: filters.getByLabel('From', { exact: true }),
    to: filters.getByLabel('To', { exact: true }),
    apply: filters.getByRole('button', { name: 'Apply', exact: true }),
    team: filters.getByLabel('Team', { exact: true }),
    repository: filters.getByLabel('Repository', { exact: true }),
    reset: filters.getByRole('button', { name: 'Reset filters' }),
  }
}

/** A KPI card addressed by the measure it shows — each card is an article with its own heading. */
export function kpiCard(page: Page, label: string): Locator {
  return dashboardSections(page)
    .kpis.getByRole('article')
    .filter({ has: page.getByRole('heading', { name: label, exact: true }) })
}

/** A finding card addressed by the scope it names, which is how the panel presents them. */
export function findingFor(page: Page, scopeName: string): Locator {
  return dashboardSections(page).attention.getByRole('listitem').filter({ hasText: scopeName })
}

/** A comparison row addressed by the scope id the server gave it, not by its position. */
export function comparisonRow(page: Page, scopeId: string): Locator {
  return dashboardSections(page).comparison.locator(`tr[data-scope-id="${scopeId}"]`)
}
