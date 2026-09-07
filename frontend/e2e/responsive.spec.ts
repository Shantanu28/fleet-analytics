/**
 * The 375px layout, measured at a real 375px viewport.
 *
 * This is the first genuine measurement of it. Earlier manual checks were made in a pane that
 * clamped the viewport to a wider width, so a constrained document width was all they could show —
 * which is not the same claim.
 */
import { expect, test } from './support/fixtures'
import { dashboardSections, openDashboard } from './support/app'
import { NORTHSTAR_ADMIN } from './support/users'

const WIDTH = 375

test.use({ viewport: { width: WIDTH, height: 812 } })

test('the page fits a 375px viewport without scrolling sideways', async ({ page }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)

  // The viewport really is 375 CSS pixels wide, so what follows is a viewport measurement.
  expect(await page.evaluate(() => window.innerWidth)).toBe(WIDTH)

  const documentWidth = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(documentWidth.scrollWidth).toBeLessThanOrEqual(documentWidth.clientWidth)

  await expect(dashboardSections(page).kpis).toBeVisible()
  await expect(dashboardSections(page).comparison).toBeVisible()
})

test('the comparison table scrolls inside its own labelled region', async ({ page }) => {
  await openDashboard(page, NORTHSTAR_ADMIN)

  const scroller = dashboardSections(page)
    .comparison.getByRole('region')
    .filter({ hasText: 'Scroll horizontally for all columns' })
  await expect(scroller).toBeVisible()

  const overflow = await scroller.evaluate((node) => ({
    scrollWidth: node.scrollWidth,
    clientWidth: node.clientWidth,
    overflowX: getComputedStyle(node).overflowX,
  }))

  // Wider than its box, and containing that width itself rather than pushing the page sideways.
  expect(overflow.scrollWidth).toBeGreaterThan(overflow.clientWidth)
  expect(overflow.overflowX).toBe('auto')
})
