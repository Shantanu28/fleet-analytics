/**
 * Tenancy and role: one organisation at a time, and one restricted field.
 *
 * Scopes are compared by identity throughout. Display names are not unique across organisations —
 * two tenants may both have a "Platform" — so a test that asserted on names could pass while the
 * wrong tenant's data was on screen.
 *
 * The blocked destination is the one field a VIEWER must never receive. It is checked in three
 * places, because leaking it in any one of them is a leak: the response body, the rendered page and
 * the URL. Assertions compare and report booleans; the value itself is never printed.
 */
import { expect, test } from './support/fixtures'
import { fetchContext, fetchDashboard, login } from './support/api'
import {
  appliedSelection,
  dashboardSections,
  dashboardResponse,
  filterControls,
  findingFor,
  openDashboard,
  openSelection,
  searchString,
  signIn,
  signOut,
} from './support/app'
import { HARBOR_ADMIN, NORTHSTAR_ADMIN, NORTHSTAR_VIEWER } from './support/users'

test('an ADMIN sees the blocked destination and a VIEWER never receives it', async ({
  page,
  scopes,
}) => {
  const selection = {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.frictionTeam.id,
    grouping: 'teams',
  }
  const { domain, distinctTasks, distinctUsers } = scopes.frictionFinding

  await openDashboard(page, NORTHSTAR_ADMIN, selection)
  const adminFinding = findingFor(page, scopes.frictionTeam.name).filter({
    hasText: 'blocked sandbox access',
  })
  await expect(adminFinding).toContainText(domain)
  await expect(adminFinding).toContainText(`${distinctTasks} tasks`)
  await expect(adminFinding).toContainText(`${distinctUsers} people`)

  await signOut(page)

  // The VIEWER's own dashboard response, captured before the request that produces it.
  await page.goto(`/${searchString(selection)}`)
  const viewerBody = page.waitForResponse((response) =>
    response.url().includes('/api/v1/analytics/dashboard'),
  )
  await signIn(page, NORTHSTAR_VIEWER)
  const responseText = await (await viewerBody).text()

  await expect(dashboardSections(page).attention).toBeVisible()
  const viewerFinding = findingFor(page, scopes.frictionTeam.name).filter({
    hasText: 'blocked network destination',
  })

  // The same finding, with the same affected population — only the destination is withheld.
  await expect(viewerFinding).toContainText(`${distinctTasks} tasks`)
  await expect(viewerFinding).toContainText(`${distinctUsers} people`)
  await expect(viewerFinding).toContainText('visible to platform admins only')

  expect(responseText.includes(domain), 'the VIEWER response body carries the domain').toBe(false)
  const renderedText = (await page.locator('body').textContent()) ?? ''
  expect(renderedText.includes(domain), 'the rendered page carries the domain').toBe(false)
  expect(page.url().includes(domain), 'the URL carries the domain').toBe(false)
})

test('each organisation only ever offers and shows its own scopes', async ({
  page,
  request,
  scopes,
}) => {
  const harborToken = await login(request, HARBOR_ADMIN)
  const harbor = await fetchContext(request, harborToken)

  await openDashboard(page, HARBOR_ADMIN)

  await expect(page.getByRole('heading', { level: 1 })).toContainText(harbor.organisationName)
  await expect(page.locator('body')).not.toContainText(scopes.organisationName)

  // Compared by id: the filter offers exactly this tenant's teams, and nothing else.
  const teamOptions = await filterControls(page).team.locator('option').evaluateAll((options) =>
    options.map((option) => (option as HTMLOptionElement).value).filter((value) => value !== ''),
  )
  expect(new Set(teamOptions)).toEqual(new Set(harbor.teams.map((team) => team.id)))
  for (const team of scopes.teams) expect(teamOptions).not.toContain(team.id)
})

test('signing into another organisation without reloading clears the previous tenant', async ({
  page, request, scopes,
}) => {
  const harborToken = await login(request, HARBOR_ADMIN)
  const harbor = await fetchContext(request, harborToken)
  await openDashboard(page, NORTHSTAR_ADMIN)
  await expect(dashboardSections(page).comparison).toContainText(scopes.budgetedTeam.name)

  // No goto/reload here: the same React tree and QueryClient must relinquish Northstar's data.
  await signOut(page)
  await expect(dashboardSections(page).kpis).toHaveCount(0)
  const response = dashboardResponse(page)
  await signIn(page, HARBOR_ADMIN)
  expect((await response).status()).toBe(200)
  await expect(dashboardSections(page).comparison).toBeVisible()
  await expect(page.getByRole('heading', { level: 1 })).toContainText(harbor.organisationName)
  await expect(page.locator('body')).not.toContainText(scopes.organisationName)

  const teamIds = await filterControls(page).team.locator('option').evaluateAll(options =>
    options.map(option => (option as HTMLOptionElement).value).filter(Boolean))
  expect(new Set(teamIds)).toEqual(new Set(harbor.teams.map(team => team.id)))
  const rowIds = await dashboardSections(page).comparison.locator('tr[data-scope-id]')
    .evaluateAll(rows => rows.map(row => row.getAttribute('data-scope-id')))
  expect(rowIds.length).toBeGreaterThan(0)
  for (const id of rowIds) {
    expect(harbor.teams.some(team => team.id === id)).toBe(true)
    expect(scopes.teams.some(team => team.id === id)).toBe(false)
  }
})

test("another organisation's team id is rejected as unknown, and Reset recovers", async ({
  page,
  request,
  scopes,
}) => {
  const harborToken = await login(request, HARBOR_ADMIN)
  const harbor = await fetchContext(request, harborToken)
  const harborLanding = await fetchDashboard(request, harborToken, {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    grouping: 'teams',
  })

  // A foreign identifier, carried in by hand and signed in against. Indistinguishable from one
  // that never existed anywhere.
  await openSelection(page, HARBOR_ADMIN, {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.budgetedTeam.id,
    grouping: 'teams',
  })

  const rejection = page.getByRole('alert').filter({ hasText: 'This selection could not be used' })
  await expect(rejection).toBeVisible()
  await expect(rejection).toContainText('not part of this organisation')
  // Nothing is rendered beneath a rejected selection: there are no figures for it.
  await expect(dashboardSections(page).kpis).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText(scopes.organisationName)

  await rejection.getByRole('button', { name: 'Reset filters' }).click()

  await expect(dashboardSections(page).kpis).toBeVisible()
  expect(appliedSelection(page)).toEqual({
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    grouping: 'teams',
  })
  // Harbor's own data, addressed by Harbor's own identities.
  const firstHarborRow = harborLanding.comparison.rows[0]
  if (firstHarborRow === undefined) throw new Error('Harbor must have a comparison row')
  const name = harbor.teams.find((team) => team.id === firstHarborRow.scopeId)?.name
  if (name === undefined) throw new Error('Harbor comparison row must identify one of its teams')
  await expect(dashboardSections(page).comparison).toContainText(name)
})
