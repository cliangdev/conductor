import { test, expect } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

const BACKEND = 'http://localhost:8080'

async function getAuthToken(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

async function createProjectViaApi(token: string, request: import('@playwright/test').APIRequestContext) {
  const res = await request.post(`${BACKEND}/api/v1/projects`, {
    data: { name: `E2E Status ${Date.now()}`, description: 'Playwright status test' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string }>
}

async function createIssueViaApi(
  token: string,
  projectId: string,
  request: import('@playwright/test').APIRequestContext
) {
  const res = await request.post(`${BACKEND}/api/v1/projects/${projectId}/issues`, {
    data: { type: 'PRD', title: `Status Test Issue ${Date.now()}` },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; title: string; status: string }>
}

test('creator can change issue status via dropdown', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  expect(token).toBeTruthy()

  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)
  await expect(page.locator('h1')).toContainText(issue.title, { timeout: 15000 })

  // The status badge for a DRAFT issue shows "DRAFT" and has a dropdown trigger (▼)
  // The StatusDropdown renders a <button> wrapping a <Badge> with the current status
  const statusTrigger = page.locator('button').filter({ hasText: /DRAFT/ })
  await expect(statusTrigger).toBeVisible({ timeout: 10000 })

  // Open the dropdown
  await statusTrigger.click()

  // The IN REVIEW option should appear in the dropdown
  const inReviewOption = page.getByRole('menuitem').filter({ hasText: /IN REVIEW/ })
  await expect(inReviewOption).toBeVisible({ timeout: 5000 })

  // Click it to transition the status
  await inReviewOption.click()

  // The status badge in the header should now reflect IN REVIEW
  await expect(page.locator('button').filter({ hasText: /IN REVIEW/ })).toBeVisible({ timeout: 10000 })
})

test('status dropdown shows IN_REVIEW as valid transition from DRAFT', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)
  await expect(page.locator('h1')).toContainText(issue.title, { timeout: 15000 })

  // Open the status dropdown
  const statusTrigger = page.locator('button').filter({ hasText: /DRAFT/ })
  await expect(statusTrigger).toBeVisible({ timeout: 10000 })
  await statusTrigger.click()

  // IN REVIEW must be present (valid transition from DRAFT)
  await expect(page.getByRole('menuitem').filter({ hasText: /IN REVIEW/ })).toBeVisible({ timeout: 5000 })
})

test('status dropdown does not show APPROVED as transition from DRAFT', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)
  await expect(page.locator('h1')).toContainText(issue.title, { timeout: 15000 })

  // Open the status dropdown
  const statusTrigger = page.locator('button').filter({ hasText: /DRAFT/ })
  await expect(statusTrigger).toBeVisible({ timeout: 10000 })
  await statusTrigger.click()

  // APPROVED must NOT be present as a valid transition from DRAFT
  // (only IN_REVIEW is valid from DRAFT per VALID_TRANSITIONS)
  await expect(page.getByRole('menuitem').filter({ hasText: /^APPROVED$/ })).not.toBeVisible()
})

test('issue list page shows DRAFT status badge for new issue', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues`)
  await expect(page.locator('h1')).toContainText('Issues', { timeout: 15000 })

  // The issue should appear in the table with DRAFT status
  await expect(page.getByRole('link', { name: issue.title })).toBeVisible({ timeout: 10000 })
  // Find the row for this issue and check that its status badge shows DRAFT
  const issueRow = page.getByRole('row').filter({ hasText: issue.title })
  await expect(issueRow.getByText('DRAFT')).toBeVisible()
})
