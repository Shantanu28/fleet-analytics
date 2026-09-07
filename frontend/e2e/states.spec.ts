/**
 * Empty, unavailable, rejected and failing states.
 *
 * The first three come from the real dataset and the real API. The last two are produced by
 * intercepting responses in the browser — clearly marked as injected, because that is what they
 * are: a 500 from `page.route` proves the page recovers from a failed request, and an injected 401
 * proves it discards the session and the tenant's data when the API refuses. Neither is evidence
 * that a real token expired, and no test hook exists in the application to make them happen.
 */
import { expect, test } from './support/fixtures'
import {
  appliedSelection,
  dashboardResponse,
  dashboardSections,
  filterControls,
  findingFor,
  kpiCard,
  openDashboard,
  openSelection,
} from './support/app'
import { NORTHSTAR_ADMIN } from './support/users'

const DASHBOARD_ROUTE = '**/api/v1/analytics/dashboard*'

test('an empty team and repository intersection reports zeros, reasons and an unevaluated budget', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN, {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.budgetedTeam.id,
    repositoryId: scopes.emptyRepositoryInBudgetedTeam.id,
    grouping: 'teams',
  })

  // A defined zero is an answer, and it is stated as one — with this metric's own population.
  const merged = kpiCard(page, 'Merged agent PRs')
  await expect(merged).toContainText('0')
  await expect(merged).toContainText('No eligible agent PRs merged during the selected period')

  // An undefined rate is not a zero, and each says which denominator was missing.
  await expect(kpiCard(page, 'Terminal PR merge rate')).toContainText(
    'No PRs reached a terminal state in this period',
  )
  await expect(kpiCard(page, 'Blended cost per merged PR')).toContainText('No merged PRs')
  await expect(kpiCard(page, 'Task completion rate')).toContainText(
    'No code-change tasks reached a terminal state',
  )

  // A repository filter leaves no budget to evaluate, and the panel says so instead of implying
  // that budgets are fine.
  const attention = dashboardSections(page).attention
  await expect(attention).toContainText('could not run')
  await attention.locator('summary').filter({ hasText: 'could not run' }).click()
  await expect(attention).toContainText('Budgets have no repository allocation')
  await expect(attention).toContainText('A check that could not run does not mean everything is fine')
  await expect(attention).not.toContainText('No configured alerts triggered')
})

test('a known empty period still shows the budget finding for its own month', async ({
  page,
  scopes,
}) => {
  // No repository filter: the budget is evaluated over its calendar month regardless of the dates
  // selected, so an empty selected period does not make it unevaluable.
  await openDashboard(page, NORTHSTAR_ADMIN, {
    from: scopes.emptyDay,
    to: scopes.emptyDay,
    teamId: scopes.budgetedTeam.id,
    grouping: 'teams',
  })

  await expect(kpiCard(page, 'Merged agent PRs')).toContainText('0')

  const finding = findingFor(page, scopes.budgetedTeam.name).filter({ hasText: 'overspend' })
  await expect(finding).toBeVisible()
  await expect(finding).toContainText('a calendar month, independent of the dates selected above')
  // The finding's period, not the empty day the user selected.
  await expect(finding).not.toContainText(`Evaluated over ${scopes.emptyDay}`)
})

test('a reversed date range is rejected with its own reason and no figures', async ({
  page,
  scopes,
}) => {
  // Signed in *at* the reversed selection: the token is in memory, so navigating after signing in
  // would end the session instead of changing the view.
  await openSelection(page, NORTHSTAR_ADMIN, {
    from: scopes.defaultTo,
    to: scopes.defaultFrom,
    grouping: 'teams',
  })

  const rejection = page.getByRole('alert').filter({ hasText: 'This selection could not be used' })
  await expect(rejection).toBeVisible()
  await expect(rejection).toContainText('The start date must not be after the end date')
  await expect(dashboardSections(page).kpis).toHaveCount(0)
  // A rejected selection is one failure with one cause, reported once.
  await expect(rejection.getByRole('button', { name: 'Reset filters' })).toHaveCount(0)
})

test('injected server failures surface an error, and Try again recovers the same selection', async ({
  page,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const selection = appliedSelection(page)

  // INJECTED: every dashboard request fails while this route is installed, so the application's own
  // retry policy runs to exhaustion before the error UI appears. The policy is not changed for the
  // test — exhausting it is the point.
  let failures = 0
  await page.route(DASHBOARD_ROUTE, async (route) => {
    failures += 1
    await route.fulfill({
      status: 500,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Internal Server Error', status: 500 }),
    })
  })

  await filterControls(page).preset(7).click()

  const failureNotice = page.getByRole('alert').filter({ hasText: 'Could not load the dashboard' })
  await expect(failureNotice).toBeVisible({ timeout: 30_000 })
  await expect(dashboardSections(page).kpis).toHaveCount(0)
  // More than one attempt was made: the retry policy really was exhausted, not bypassed.
  expect(failures).toBeGreaterThan(1)
  const rejectedSelection = appliedSelection(page)

  await page.unroute(DASHBOARD_ROUTE)

  const recovered = dashboardResponse(page)
  await failureNotice.getByRole('button', { name: 'Try again' }).click()
  expect((await recovered).status()).toBe(200)

  await expect(dashboardSections(page).kpis).toBeVisible()
  await expect(failureNotice).toHaveCount(0)
  // Retrying keeps the filters the user chose; it does not fall back to the landing view.
  expect(appliedSelection(page)).toEqual(rejectedSelection)
  expect(appliedSelection(page)).not.toEqual(selection)
})

test('an injected unauthorized response ends the session and clears the tenant data', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  await expect(dashboardSections(page).comparison).toContainText(scopes.budgetedTeam.name)

  // INJECTED: the API refuses the next authenticated dashboard request. This exercises the
  // application's handling of a 401; it is not evidence that a real JWT expired.
  let refused = 0
  await page.route(DASHBOARD_ROUTE, async (route) => {
    refused += 1
    await route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'urn:fleet:problem:unauthenticated', status: 401 }),
    })
  })

  // A fresh request, made with the session's own credentials, is what must be refused — not a
  // cached answer being re-read.
  await filterControls(page).preset(7).click()

  await expect(page.getByLabel('Username', { exact: true })).toBeVisible()
  expect(refused).toBeGreaterThan(0)
  await expect(page.getByRole('button', { name: 'Sign out' })).toHaveCount(0)

  const body = page.locator('body')
  await expect(body).not.toContainText(scopes.organisationName)
  await expect(body).not.toContainText(scopes.budgetedTeam.name)
  await expect(body).not.toContainText(scopes.failureSpikeRepository.name)
})
