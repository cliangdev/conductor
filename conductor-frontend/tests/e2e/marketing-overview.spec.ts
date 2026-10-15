import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

const BACKEND = 'http://localhost:8080'

async function getAuthToken(page: Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

async function createProjectViaApi(token: string, request: APIRequestContext) {
  const res = await request.post(`${BACKEND}/api/v1/projects`, {
    data: { name: `E2E Marketing ${Date.now()}`, description: 'Playwright marketing overview test' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; name: string }>
}

test('marketing overview: funnel, channel cards, drill-down, window and mobile layout', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })
  const token = await getAuthToken(page)
  expect(token).toBeTruthy()
  const project = await createProjectViaApi(token, request)
  await setActiveProjectInStorage(page, project.id)

  // 1. Open Marketing → Overview. The route is a sibling of "What's working"; the sidebar entry only
  // appears once the workspace has a Marketing workflow, so navigate by URL as the other specs do.
  await page.goto(`/app/projects/${project.id}/marketing/overview`)
  await expect(page.locator('h1')).toContainText('Marketing overview', { timeout: 15000 })

  // 2. The funnel and the five channel cards, in order.
  // (A workspace with nothing connected shows the empty state in place of the funnel.)
  await expect(
    page.getByRole('region', { name: 'Funnel' }).or(page.getByText('Nothing is connected yet')),
  ).toBeVisible({ timeout: 15000 })
  for (const name of ['Web', 'Search', 'Social', 'App Store', 'Apple Ads']) {
    await expect(page.getByRole('region', { name, exact: true })).toBeVisible()
  }

  // 3. Open the Search detail page from its card.
  await page.getByRole('region', { name: 'Search', exact: true }).getByRole('link', { name: 'Search' }).click()
  await page.waitForURL(/\/marketing\/overview\/search/)
  await expect(page.getByRole('link', { name: /Marketing overview/ })).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('tab', { name: '90d' })).toBeVisible()
  await page.getByRole('link', { name: /Marketing overview/ }).click()
  await page.waitForURL(/\/marketing\/overview$/)

  // 5. Switch to 7d: the toggle is selected and the choice survives a reload.
  await page.getByRole('tab', { name: '7d' }).click()
  await expect(page.getByRole('tab', { name: '7d' })).toHaveAttribute('aria-selected', 'true')
  await page.reload()
  await expect(page.getByRole('tab', { name: '7d' })).toHaveAttribute('aria-selected', 'true', { timeout: 15000 })

  // 6. No horizontal page scroll at phone width.
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('region', { name: 'Search', exact: true })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
  await page.setViewportSize({ width: 1280, height: 800 })

  // 4. Set a target on a metric, see it on the card, then remove it. Needs a channel with metrics
  // (the local profile's mock connectors); a bare workspace has none, so this part skips itself.
  const setTarget = page.getByRole('button', { name: /^Set target for/ }).first()
  const hasMetrics = await setTarget.isVisible().catch(() => false)
  test.skip(!hasMetrics, 'no connected channel with metrics in this workspace')

  await setTarget.click()
  await page.getByLabel(/^Target/).fill('4')
  await page.getByRole('button', { name: 'Save target' }).click()
  await expect(page.getByText(/Target .* there|Target /).first()).toBeVisible({ timeout: 10000 })

  await page.getByRole('button', { name: /^Edit target for/ }).first().click()
  await page.getByRole('button', { name: 'Remove target' }).click()
  await expect(page.getByRole('button', { name: /^Edit target for/ })).toHaveCount(0, { timeout: 10000 })
})
