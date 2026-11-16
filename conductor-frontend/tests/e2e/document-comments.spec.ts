import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

/**
 * Margin-rail comments on a markdown document: the desktop rail (cards lined up with their highlights,
 * click sync, j/k) and the phone sheet (tap a highlight, "Comments · N").
 *
 * Like the other specs here this runs against a live backend (BACKEND below) with the login from
 * `auth.setup.ts`; it creates its own project, work item, document and comments through the API.
 */
const BACKEND = 'http://localhost:8080'

const FILLER =
  'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ' +
  'Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.'

const QUOTES = ['onboarding should take under five minutes', 'retention is the metric that matters', 'launch before the holidays']

const CONTENT = `# Plan

## Goals

${FILLER}

Our first goal: ${QUOTES[0]}, for every new account.

${FILLER}

${FILLER}

## Metrics

Remember that ${QUOTES[1]}, more than raw signups.

${FILLER}

${FILLER}

## Timeline

The team agreed to ${QUOTES[2]}, with a soft launch first.

${FILLER}
`

async function token(page: Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

const headers = (t: string) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })

async function seed(page: Page, request: APIRequestContext) {
  const ids = await seedIds(page, request)
  await openIssue(page, ids)
  return ids
}

interface Seeded {
  token: string
  projectId: string
  issueId: string
  docId: string
}

async function openIssue(page: Page, ids: Seeded, query = '') {
  await setActiveProjectInStorage(page, ids.projectId)
  await page.goto(`/app/projects/${ids.projectId}/issues/${ids.issueId}${query}`)
  await expect(page.getByRole('heading', { name: 'Plan', level: 1 })).toBeVisible({ timeout: 15000 })
}

async function seedIds(page: Page, request: APIRequestContext): Promise<Seeded> {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })
  const t = await token(page)
  expect(t).toBeTruthy()

  const project = await (
    await request.post(`${BACKEND}/api/v1/projects`, {
      data: { name: `E2E Doc Comments ${Date.now()}`, description: 'Playwright document comments test' },
      headers: headers(t),
    })
  ).json()
  const issue = await (
    await request.post(`${BACKEND}/api/v1/projects/${project.id}/issues`, {
      data: { type: 'PRD', title: `Comments test ${Date.now()}` },
      headers: headers(t),
    })
  ).json()
  const doc = await (
    await request.post(`${BACKEND}/api/v1/projects/${project.id}/issues/${issue.id}/documents`, {
      data: { filename: 'prd.md', content: CONTENT, contentType: 'text/markdown' },
      headers: headers(t),
    })
  ).json()

  for (const [i, exact] of QUOTES.entries()) {
    const res = await request.post(`${BACKEND}/api/v2/projects/${project.id}/comments`, {
      data: {
        target: { workItemId: issue.id, documentId: doc.id },
        content: `Thread ${i + 1} body`,
        anchor: { exact },
      },
      headers: headers(t),
    })
    expect(res.ok()).toBeTruthy()
  }

  return { token: t, projectId: project.id, issueId: issue.id, docId: doc.id }
}

/** Where the quoted words are on screen. The highlight is painted by the CSS Custom Highlight API, so it has no element: find the text. */
async function rectOf(page: Page, exact: string) {
  return page.evaluate((text) => {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const i = (n.nodeValue ?? '').indexOf(text)
      if (i < 0) continue
      const r = document.createRange()
      r.setStart(n, i)
      r.setEnd(n, i + text.length)
      const b = r.getBoundingClientRect()
      return { top: b.top, left: b.left, width: b.width, height: b.height }
    }
    return null
  }, exact)
}

const card = (page: Page, n: number) => page.locator('[data-comment-id]', { hasText: `Thread ${n} body` })

test.describe('desktop', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('cards sit beside their highlights, clicks and j/k keep them in sync', async ({ page, request }) => {
    await seed(page, request)

    // The rail is docked, with a card per thread.
    await expect(card(page, 1)).toBeVisible({ timeout: 10000 })

    // Focusing a thread puts its card exactly at its words (the card starts 8px above the first line).
    for (const [i, exact] of QUOTES.entries()) {
      await card(page, i + 1).click()
      await expect(card(page, i + 1)).toHaveAttribute('data-active', 'true')
      await page.waitForTimeout(500) // the scroll to the highlight settles
      const words = await rectOf(page, exact)
      const box = await card(page, i + 1).boundingBox()
      expect(words).not.toBeNull()
      expect(Math.abs(box!.y - (words!.top - 8))).toBeLessThanOrEqual(4)
    }

    // Clicking the words focuses their card.
    const words = (await rectOf(page, QUOTES[0]))!
    await page.mouse.click(words.left + 4, words.top + words.height / 2)
    await expect(card(page, 1)).toHaveAttribute('data-active', 'true')
    await expect(card(page, 2)).not.toHaveAttribute('data-active', 'true')

    // j and k step through the threads in reading order.
    await page.keyboard.press('Escape')
    await page.keyboard.press('j')
    await expect(card(page, 1)).toHaveAttribute('data-active', 'true')
    await page.keyboard.press('j')
    await expect(card(page, 2)).toHaveAttribute('data-active', 'true')
    await page.keyboard.press('k')
    await expect(card(page, 1)).toHaveAttribute('data-active', 'true')
  })
})

test.describe('mentions, suggested edits and deep links', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('@ opens the picker and the mention shows as a chip', async ({ page, request }) => {
    await seed(page, request)
    await page.getByRole('button', { name: 'Comment on whole document' }).click()
    const box = page.getByRole('textbox', { name: 'Comment on whole document' })
    await box.fill('Looping in @')
    const list = page.getByRole('listbox', { name: 'Mention someone' })
    await expect(list).toBeVisible()
    await page.keyboard.press('Enter')
    // The box shows a readable @Name, never the stored token.
    await expect(box).toHaveValue(/@[^[\]]+ $/)
    await page.keyboard.press('Meta+Enter')
    await expect(page.locator('[data-mention]').first()).toBeVisible({ timeout: 10000 })
    await expect(page.locator('body')).not.toContainText('(user:')
  })

  test('a suggested edit shows old and new text and Accept rewrites the document', async ({ page, request }) => {
    const ids = await seedIds(page, request)
    const res = await request.post(`${BACKEND}/api/v2/projects/${ids.projectId}/comments`, {
      data: {
        target: { workItemId: ids.issueId, documentId: ids.docId },
        content: 'Tighter wording',
        anchor: { exact: QUOTES[2] },
        suggestion: { text: 'launch in the new year' },
      },
      headers: headers(ids.token),
    })
    expect(res.ok()).toBeTruthy()
    await openIssue(page, ids)

    const suggestion = page.locator('[data-comment-id]', { hasText: 'Tighter wording' })
    await expect(suggestion).toContainText('Suggestion')
    await expect(suggestion.locator('del')).toHaveText(QUOTES[2])
    await expect(suggestion.locator('ins')).toHaveText('launch in the new year')

    await suggestion.getByRole('button', { name: 'Accept' }).click()
    await expect(suggestion).toContainText('Accepted by')
    await expect(page.getByText('launch in the new year', { exact: false }).first()).toBeVisible()
  })

  test('?comment= focuses that thread and then leaves the address', async ({ page, request }) => {
    const ids = await seedIds(page, request)
    const list = await (
      await request.get(
        `${BACKEND}/api/v2/projects/${ids.projectId}/comments?workItemId=${ids.issueId}&documentId=${ids.docId}&status=all`,
        { headers: headers(ids.token) }
      )
    ).json()
    const target = list.find((c: { content: string }) => c.content === 'Thread 2 body')
    await openIssue(page, ids, `?doc=${ids.docId}&comment=${target.id}`)
    await expect(card(page, 2)).toHaveAttribute('data-active', 'true', { timeout: 10000 })
    await expect(page).not.toHaveURL(/comment=/)
  })
})

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

  test('tapping a highlight opens its thread in a bottom sheet; Comments · N lists them all', async ({ page, request }) => {
    await seed(page, request)

    // Highlights only: no rail on screen.
    await expect(page.locator('#document-comment-rail')).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Comments · 3$/ })).toBeVisible({ timeout: 10000 })

    await page.locator('p', { hasText: QUOTES[0] }).scrollIntoViewIfNeeded()
    const words = (await rectOf(page, QUOTES[0]))!
    await page.touchscreen.tap(words.left + 4, words.top + words.height / 2)

    const sheet = page.getByRole('dialog')
    await expect(sheet).toBeVisible()
    await expect(sheet).toContainText('Thread 1 body')
    await expect(sheet).not.toContainText('Thread 2 body')
    // Docked to the bottom edge of the screen.
    const box = (await sheet.boundingBox())!
    expect(Math.round(box.y + box.height)).toBe(844)

    await page.keyboard.press('Escape')
    await expect(sheet).toBeHidden()

    await page.getByRole('button', { name: /^Comments · 3$/ }).tap()
    await expect(sheet).toBeVisible()
    for (const n of [1, 2, 3]) await expect(sheet).toContainText(`Thread ${n} body`)
    await expect(sheet.getByRole('region', { name: 'Whole document' })).toBeVisible()
  })
})
