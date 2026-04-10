import { test, expect } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

const BACKEND = 'http://localhost:8080'

async function getAuthToken(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

async function createProjectViaApi(token: string, request: import('@playwright/test').APIRequestContext) {
  const res = await request.post(`${BACKEND}/api/v1/projects`, {
    data: { name: `E2E Doc Render ${Date.now()}`, description: 'Playwright document rendering test' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; name: string }>
}

async function createIssueViaApi(
  token: string,
  projectId: string,
  request: import('@playwright/test').APIRequestContext
) {
  const res = await request.post(`${BACKEND}/api/v1/projects/${projectId}/issues`, {
    data: { type: 'PRD', title: `Doc Render Test Issue ${Date.now()}` },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; title: string }>
}

async function createDocumentViaApi(
  token: string,
  projectId: string,
  issueId: string,
  content: string,
  request: import('@playwright/test').APIRequestContext
) {
  const res = await request.post(`${BACKEND}/api/v1/projects/${projectId}/issues/${issueId}/documents`, {
    data: { filename: 'prd.md', content, contentType: 'text/markdown' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; filename: string }>
}

const MARKDOWN_CONTENT = `# Product Requirements Document

## Overview

This PRD describes the **feature requirements** for the new system.

## Goals

- Improve user onboarding
- Reduce time to first value

## Non-Goals

This document does _not_ cover infrastructure changes.
`

test('markdown PRD renders headings and formatted text in issue detail', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  expect(token).toBeTruthy()

  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)
  await createDocumentViaApi(token, project.id, issue.id, MARKDOWN_CONTENT, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)
  await expect(page.getByRole('heading', { name: issue.title, level: 1 }).first()).toBeVisible({ timeout: 15000 })

  // The sidebar should list the document filename
  await expect(page.getByText('prd.md')).toBeVisible({ timeout: 10000 })

  // Markdown headings render as semantic heading elements
  await expect(page.getByRole('heading', { name: 'Product Requirements Document' })).toBeVisible({ timeout: 10000 })
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Goals', exact: true })).toBeVisible()

  // Bold text renders as <strong>
  await expect(page.locator('strong').filter({ hasText: 'feature requirements' })).toBeVisible()

  // Italic text renders as <em>
  await expect(page.locator('em').filter({ hasText: 'not' })).toBeVisible()

  // List items render
  await expect(page.getByText('Improve user onboarding')).toBeVisible()
  await expect(page.getByText('Reduce time to first value')).toBeVisible()
})

test('markdown PRD is auto-selected when it is the only document', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)
  await createDocumentViaApi(token, project.id, issue.id, MARKDOWN_CONTENT, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)
  await expect(page.getByRole('heading', { name: issue.title, level: 1 }).first()).toBeVisible({ timeout: 15000 })

  // The document is auto-selected — main area shows rendered content without clicking
  await expect(page.getByRole('heading', { name: 'Product Requirements Document' })).toBeVisible({ timeout: 10000 })

  // The sidebar filename button has the active highlight style (text-blue-700)
  const docButton = page.getByRole('button', { name: 'prd.md' })
  await expect(docButton).toBeVisible()
  await expect(docButton).toHaveClass(/text-blue-700/)
})
