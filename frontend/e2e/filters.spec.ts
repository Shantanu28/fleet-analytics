/**
 * Filtering: the URL, the label and the request always describe one selection.
 *
 * Each assertion pairs what the page says with what the server was actually asked. Checking only
 * the label would pass while the figures beneath it answered a different question, which is the one
 * failure a filtered dashboard must not have.
 */
import { expect, test } from './support/fixtures'
import {
  appliedSelection,
  dashboardResponse,
  dashboardSections,
  filterControls,
  openDashboard,
  requestedSelection,
} from './support/app'
import { NORTHSTAR_ADMIN } from './support/users'

test('an unparameterised visit canonicalises to explicit dates that match the request', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN)

  expect(appliedSelection(page)).toEqual({
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    grouping: 'teams',
  })
  await expect(page.getByText('Showing')).toContainText('Aug 2026')
})

test('presets move the URL and the request together', async ({ page, scopes }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)

  for (const days of [7, 90]) {
    const response = dashboardResponse(page)
    await filterControls(page).preset(days).click()
    const requested = requestedSelection(await response)

    const applied = appliedSelection(page)
    expect(applied.to).toBe(scopes.defaultTo)
    expect(requested.from).toBe(applied.from)
    expect(requested.to).toBe(applied.to)
    await expect(filterControls(page).preset(days)).toHaveAttribute('aria-pressed', 'true')
  }
})

test('a custom range applies once, as one decision', async ({ page, scopes }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const controls = filterControls(page)
  const original = appliedSelection(page)

  // Editing one date is a draft: nothing is requested and the applied range does not move.
  await controls.from.fill(scopes.budgetMonth.from)
  expect(appliedSelection(page)).toEqual(original)
  await expect(controls.apply).toBeEnabled()

  await controls.to.fill(scopes.budgetMonth.to)
  const response = dashboardResponse(page)
  await controls.apply.click()
  const requested = requestedSelection(await response)

  expect(appliedSelection(page)).toMatchObject({
    from: scopes.budgetMonth.from,
    to: scopes.budgetMonth.to,
  })
  expect(requested.from).toBe(scopes.budgetMonth.from)
  expect(requested.to).toBe(scopes.budgetMonth.to)
  await expect(dashboardSections(page).filters).toContainText('Custom range applied')
})

test('team and repository filters are named on screen and carried in the request', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const controls = filterControls(page)

  const teamResponse = dashboardResponse(page)
  await controls.team.selectOption(scopes.budgetedTeam.id)
  expect(requestedSelection(await teamResponse).teamId).toBe(scopes.budgetedTeam.id)

  const repositoryResponse = dashboardResponse(page)
  await controls.repository.selectOption(scopes.failureSpikeRepository.id)
  const requested = requestedSelection(await repositoryResponse)
  expect(requested.teamId).toBe(scopes.budgetedTeam.id)
  expect(requested.repositoryId).toBe(scopes.failureSpikeRepository.id)

  await expect(page.getByText(`Team: ${scopes.budgetedTeam.name}`)).toBeVisible()
  await expect(
    page.getByText(`Repository: ${scopes.failureSpikeRepository.name}`),
  ).toBeVisible()
})

test('switching the table grouping changes only the grouping', async ({ page, scopes }) => {
  await openDashboard(page, NORTHSTAR_ADMIN, {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.budgetedTeam.id,
    grouping: 'teams',
  })

  const response = dashboardResponse(page)
  await dashboardSections(page)
    .comparison.getByRole('button', { name: 'Repositories' })
    .click()
  const requested = requestedSelection(await response)

  expect(requested).toMatchObject({
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.budgetedTeam.id,
    grouping: 'repositories',
  })
  await expect(dashboardSections(page).comparison).toContainText('Comparison — repositories')
})

test('Reset returns to the default range with no scope filters', async ({ page, scopes }) => {
  await openDashboard(page, NORTHSTAR_ADMIN, {
    from: scopes.budgetMonth.from,
    to: scopes.budgetMonth.to,
    teamId: scopes.budgetedTeam.id,
    repositoryId: scopes.failureSpikeRepository.id,
    grouping: 'repositories',
  })

  const response = dashboardResponse(page)
  await filterControls(page).reset.click()
  await response

  expect(appliedSelection(page)).toEqual({
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    grouping: 'teams',
  })
  await expect(page.getByText('All teams · All repositories · Grouped by teams')).toBeVisible()
})

test('Back and Forward restore previous selections, grouping included', async ({
  page,
  scopes,
}) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const landing = appliedSelection(page)

  const teamResponse = dashboardResponse(page)
  await filterControls(page).team.selectOption(scopes.budgetedTeam.id)
  await teamResponse

  const groupingResponse = dashboardResponse(page)
  await dashboardSections(page)
    .comparison.getByRole('button', { name: 'Repositories' })
    .click()
  await groupingResponse
  const grouped = appliedSelection(page)

  await page.goBack()
  await expect(dashboardSections(page).comparison).toContainText('Comparison — teams')
  expect(appliedSelection(page)).toMatchObject({ teamId: scopes.budgetedTeam.id })

  await page.goBack()
  await expect(filterControls(page).team).toHaveValue('')
  expect(appliedSelection(page)).toEqual(landing)

  await page.goForward()
  await page.goForward()
  await expect(dashboardSections(page).comparison).toContainText('Comparison — repositories')
  expect(appliedSelection(page)).toEqual(grouped)
})
