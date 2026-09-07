/**
 * Investigation: following a finding, and coming back.
 *
 * Each journey checks three things — that the destination filters are what the finding promised,
 * that the destination was actually brought into view, and that Back restores the exact selection
 * the user left. A link that changes the right filters but strands the user mid-page has not
 * answered their question.
 */
import { expect, test } from './support/fixtures'
import {
  appliedSelection,
  comparisonRow,
  dashboardSections,
  dashboardResponse,
  requestedSelection,
  findingFor,
  openDashboard,
} from './support/app'
import { NORTHSTAR_ADMIN } from './support/users'

test('a budget finding opens its own month on the spend trend', async ({ page, scopes }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const origin = appliedSelection(page)

  const finding = findingFor(page, scopes.budgetedTeam.name).filter({ hasText: 'overspend' })
  await expect(finding).toContainText('a calendar month, independent of the dates selected above')
  await finding.getByRole('button', { name: `Inspect ${scopes.budgetedTeam.name} spend` }).click()

  // The finding's own month, its own team, and no repository filter — budgets have no repository
  // allocation, so one would leave nothing to inspect.
  await expect(dashboardSections(page).trends).toBeVisible()
  expect(appliedSelection(page)).toEqual({
    from: scopes.budgetMonth.from,
    to: scopes.budgetMonth.to,
    teamId: scopes.budgetedTeam.id,
    grouping: 'teams',
  })
  expect(appliedSelection(page).repositoryId).toBeUndefined()

  // The period changed, and the page says so rather than letting the figures look like the ones
  // the user had selected.
  await expect(
    page.getByRole('status').filter({ hasText: 'the finding’s own reporting period' }),
  ).toBeVisible()
  // Arrived at, not merely scrolled near: a keyboard user lands on the destination section.
  await expect(page.locator('#spend-trend')).toBeFocused()

  await page.goBack()
  await expect(dashboardSections(page).kpis).toBeVisible()
  expect(appliedSelection(page)).toEqual(origin)
  await expect(page.getByRole('status').filter({ hasText: 'reporting period' })).toHaveCount(0)
})

test('a repository failure spike opens the table on that row, keeping the team filter', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN, {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.budgetedTeam.id,
    grouping: 'teams',
  })
  const origin = appliedSelection(page)

  const finding = findingFor(page, scopes.failureSpikeRepository.name).filter({
    hasText: 'failures rose',
  })
  await finding
    .getByRole('button', { name: `Inspect ${scopes.failureSpikeRepository.name}` })
    .click()

  // Dates and the team survive; the repository and the grouping are what the finding sets.
  expect(appliedSelection(page)).toEqual({
    from: origin.from!,
    to: origin.to!,
    teamId: scopes.budgetedTeam.id,
    repositoryId: scopes.failureSpikeRepository.id,
    grouping: 'repositories',
  })

  const row = comparisonRow(page, scopes.failureSpikeRepository.id)
  await expect(row).toHaveAttribute('aria-current', 'true')
  await expect(row).toBeInViewport()
  await expect(row).toContainText(scopes.failureSpikeRepository.name)

  await page.goBack()
  expect(appliedSelection(page)).toEqual(origin)
  await expect(dashboardSections(page).comparison).toContainText('Comparison — teams')
  await expect(
    dashboardSections(page).comparison.locator('tr[aria-current="true"]'),
  ).toHaveCount(0)
})

test('a blocked-destination finding recalculates the findings for its narrower scope', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN, {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.frictionTeam.id,
    grouping: 'teams',
  })
  const origin = appliedSelection(page)

  const finding = findingFor(page, scopes.frictionTeam.name).filter({
    hasText: 'blocked sandbox access',
  })
  await expect(finding).toContainText('findings are recalculated for the narrower scope')
  const refresh = dashboardResponse(page)
  await finding.getByRole('button', { name: `Inspect ${scopes.frictionTeam.name}` }).click()
  const refreshed = await refresh
  expect(refreshed.status()).toBe(200)
  expect(requestedSelection(refreshed)).toEqual(origin)
  await refreshed.finished()

  // This finding's own scope *is* the applied team, so the destination selection matches the origin
  // and what is under test is the recalculation, not a filter change: the cached answer is dropped
  // and the panel re-evaluated, so the evidence on screen is the recomputed evidence.
  const attention = dashboardSections(page).attention
  await expect(attention).toBeVisible()
  await expect(attention.getByRole('status')).toHaveText(
    'Findings recalculated for the selected filters.',
  )
  await expect(attention.locator('article[aria-current]')).toHaveCount(0)
  // The panel is the destination, and it is reached whether or not the finding survived — the
  // recomputed answer is what the user came for.
  await expect(page.locator('#attention')).toBeFocused()
  expect(appliedSelection(page)).toEqual(origin)
  await expect(attention).toContainText(scopes.frictionFinding.domain)
  await expect(attention).toContainText(`${scopes.frictionFinding.distinctTasks} tasks`)
})
