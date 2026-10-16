import { test, expect, type APIRequestContext } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

const BACKEND = 'http://localhost:8080'

// The local profile's mock connectors feed daily metrics on a 60s scheduler tick, so seeding a fresh
// workspace takes a while. Allow up to 3 minutes for Search to report values.
const SEED_TIMEOUT_MS = 180_000

let projectId = ''

async function authedApi(request: APIRequestContext) {
  const login = await request.post(`${BACKEND}/api/v1/auth/local`, {
    data: { email: 'playwright@example.com', password: 'conductor' },
  })
  expect(login.ok()).toBeTruthy()
  const { accessToken } = (await login.json()) as { accessToken: string }
  return { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' }
}

test.describe('marketing overview', () => {
  test.beforeAll(async ({ playwright }) => {
    test.setTimeout(SEED_TIMEOUT_MS + 60_000)
    const request = await playwright.request.newContext()
    const headers = await authedApi(request)

    const project = await request.post(`${BACKEND}/api/v1/projects`, {
      data: { name: `E2E Marketing ${Date.now()}`, description: 'Playwright marketing overview test' },
      headers,
    })
    expect(project.ok()).toBeTruthy()
    projectId = ((await project.json()) as { id: string }).id

    const web = await request.post(`${BACKEND}/api/v1/projects/${projectId}/integrations/posthog/connections`, {
      data: { label: 'Web', apiKey: 'x', configJson: { projectId: '1', conversionEvent: 'signup_click' } },
      headers,
    })
    expect(web.ok()).toBeTruthy()
    const search = await request.post(`${BACKEND}/api/v1/projects/${projectId}/integrations/gsc/connections`, {
      data: { label: 'Search', apiKey: 'x', configJson: { siteUrl: 'sc-domain:example.com' } },
      headers,
    })
    expect(search.ok()).toBeTruthy()
    const store = await request.post(
      `${BACKEND}/api/v1/projects/${projectId}/integrations/app-store-connect/connections`,
      {
        data: {
          label: 'Store',
          apiKey: '-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----',
          configJson: { issuerId: 'issuer', keyId: 'key', appId: '123456789' },
        },
        headers,
      },
    )
    expect(store.ok()).toBeTruthy()

    // Wait for the feed scheduler to pull mock data: Search and the App Store have metrics with values.
    for (const channelName of ['SEARCH', 'STORE']) {
      await expect
        .poll(
          async () => {
            const res = await request.get(`${BACKEND}/api/v2/projects/${projectId}/marketing/overview?window=28d`, {
              headers,
            })
            if (!res.ok()) return false
            const body = (await res.json()) as {
              channels: { channel: string; metrics: { value: number | null }[] }[]
            }
            const channel = body.channels.find((c) => c.channel === channelName)
            return !!channel && channel.metrics.some((m) => m.value !== null && m.value !== undefined)
          },
          { timeout: SEED_TIMEOUT_MS, intervals: [5_000] },
        )
        .toBe(true)
    }
    await request.dispose()
  })

  test('funnel, channel cards, drill-down, targets, window and mobile layout', async ({ page }) => {
    await page.goto('/app/projects')
    await page.waitForURL(/\/app\/projects/, { timeout: 15000 })
    await setActiveProjectInStorage(page, projectId)

    // 1. Open Marketing → Overview. The sidebar entry only appears once the workspace has a Marketing
    // workflow, so navigate by URL as the other specs do.
    await page.goto(`/app/projects/${projectId}/marketing/overview`)
    await expect(page.locator('h1')).toContainText('Marketing overview', { timeout: 15000 })

    // 2. The funnel and the five channel cards.
    await expect(page.getByRole('region', { name: 'Funnel' })).toBeVisible({ timeout: 15000 })
    const search = page.getByRole('region', { name: 'Search', exact: true })
    for (const name of ['Web', 'Search', 'Social', 'App Store', 'Ads']) {
      await expect(page.getByRole('region', { name, exact: true })).toBeVisible()
    }

    // 2b. The App Store is a real channel now: it shows values, not "coming soon", and its detail page
    // breaks the numbers down by traffic source.
    const store = page.getByRole('region', { name: 'App Store', exact: true })
    await expect(store).not.toContainText(/coming soon/i)
    await expect(store).toContainText('Downloads')
    await store.getByRole('link', { name: 'App Store', exact: true }).click()
    await page.waitForURL(/\/marketing\/overview\/store/)
    await expect(page.getByRole('region', { name: 'By traffic source' })).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('region', { name: 'By traffic source' })).toContainText('App Store search')
    await page.getByRole('link', { name: /Marketing overview/ }).click()
    await page.waitForURL(/\/marketing\/overview$/)

    // 3. Open the Search detail page from its card. (The card also has a "Connect …" link in some
    // states, so match the heading link exactly.)
    await search.getByRole('link', { name: 'Search', exact: true }).click()
    await page.waitForURL(/\/marketing\/overview\/search/)
    await expect(page.getByRole('link', { name: /Marketing overview/ })).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('tab', { name: '90d' })).toBeVisible()
    await page.getByRole('link', { name: /Marketing overview/ }).click()
    await page.waitForURL(/\/marketing\/overview$/)
    await expect(search).toBeVisible()

    // 4. Set a target on a Search metric, see it on the card, then remove it.
    await search.getByRole('button', { name: /^Set target for/ }).first().click()
    await page.locator('#target-value').fill('5')
    await page.getByRole('button', { name: 'Save target' }).click()
    await expect(search.getByRole('button', { name: /^Edit target for/ })).toHaveCount(1, { timeout: 10000 })
    await expect(search).toContainText(/Target/)

    await search.getByRole('button', { name: /^Edit target for/ }).click()
    await page.getByRole('button', { name: 'Remove target' }).click()
    await expect(search.getByRole('button', { name: /^Edit target for/ })).toHaveCount(0, { timeout: 10000 })

    // 5. Switch to 7d: the toggle is selected and the choice survives a reload.
    await page.getByRole('tab', { name: '7d' }).click()
    await expect(page.getByRole('tab', { name: '7d' })).toHaveAttribute('aria-selected', 'true')
    await page.reload()
    await expect(page.getByRole('tab', { name: '7d' })).toHaveAttribute('aria-selected', 'true', { timeout: 15000 })

    // 6. No horizontal page scroll at phone width.
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(page.getByRole('region', { name: 'Search', exact: true })).toBeVisible()
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })
})
