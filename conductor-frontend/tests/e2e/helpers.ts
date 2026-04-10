import { expect, type Page } from '@playwright/test'

/**
 * Set the active project in localStorage so the navbar project selector reflects it.
 * Call this before navigating to a project URL in tests that create projects via API.
 */
export async function setActiveProjectInStorage(page: Page, projectId: string) {
  await page.evaluate((id) => localStorage.setItem('active_project_id', id), projectId)
}

/**
 * Assert the project selector dropdown trigger shows the given project name.
 */
export async function expectActiveProject(page: Page, projectName: string) {
  await expect(page.getByTestId('project-selector')).toContainText(projectName)
}

/**
 * Open the project selector dropdown and switch to the given project by name.
 */
export async function selectProject(page: Page, projectName: string) {
  await page.getByTestId('project-selector').click()
  await page.getByRole('menuitem', { name: projectName, exact: true }).click()
}
