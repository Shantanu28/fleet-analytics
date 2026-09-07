/**
 * Access and identity: signing in, signing out, and what survives a reload.
 *
 * The token lives in memory only, so a reload is a real sign-out — that is the design, and this
 * proves the selected view is restored from the URL afterwards rather than lost.
 */
import { expect, test } from './support/fixtures'
import { appliedSelection, dashboardSections, openDashboard, signIn, signOut } from './support/app'
import { NORTHSTAR_ADMIN } from './support/users'

test('signing in shows the organisation, the demo framing and the reporting cutoff', async ({
  page,
  scopes,
}) => {
  await page.goto('/')
  await signIn(page, NORTHSTAR_ADMIN)

  await expect(page.getByRole('heading', { level: 1 })).toContainText(scopes.organisationName)
  await expect(page.getByText('Synthetic demo data', { exact: true })).toBeVisible()
  // The cutoff is stated as a date, never as "today": the dataset is fixed.
  await expect(page.getByText(/Complete through \d{1,2} \w{3} \d{4} \(UTC\)/)).toBeVisible()
  await expect(dashboardSections(page).kpis).toBeVisible()
})

test('signing out leaves no tenant data on the page', async ({ page, scopes }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)
  const teamName = scopes.budgetedTeam.name
  await expect(dashboardSections(page).comparison).toContainText(teamName)

  await signOut(page)

  await expect(page.getByLabel('Username', { exact: true })).toBeVisible()
  const body = page.locator('body')
  await expect(body).not.toContainText(scopes.organisationName)
  await expect(body).not.toContainText(teamName)
  await expect(body).not.toContainText(scopes.failureSpikeRepository.name)
})

test('a reload of a filtered view requires signing in, then restores that exact selection', async ({
  page,
  scopes,
}) => {
  const selection = {
    from: scopes.defaultFrom,
    to: scopes.defaultTo,
    teamId: scopes.budgetedTeam.id,
    grouping: 'repositories',
  }
  await openDashboard(page, NORTHSTAR_ADMIN, selection)
  expect(appliedSelection(page)).toEqual(selection)

  await page.reload()

  // In-memory token: the reload ends the session, and the sign-in form comes back.
  await expect(page.getByLabel('Username', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sign out' })).toHaveCount(0)

  await signIn(page, NORTHSTAR_ADMIN)

  // Nothing was re-entered: the URL carried the selection across the sign-in.
  expect(appliedSelection(page)).toEqual(selection)
  await expect(dashboardSections(page).comparison).toContainText('Comparison — repositories')
})
