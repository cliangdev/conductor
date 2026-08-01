import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('@/lib/api', () => ({ apiPatch: vi.fn() }))
vi.mock('@/lib/docs-api', () => ({ getFolders: vi.fn() }))

import { apiPatch } from '@/lib/api'
import { getFolders } from '@/lib/docs-api'
import { MoveDocDialog } from './MoveDocDialog'

const FOLDERS = [
  { id: 'f-plans', projectId: 'proj-1', parentId: null, name: 'Plans', createdAt: '', updatedAt: '' },
  { id: 'f-archive', projectId: 'proj-1', parentId: null, name: 'Archive', createdAt: '', updatedAt: '' },
]

function renderDialog(currentFolderId: string | null) {
  return render(
    <MoveDocDialog
      projectId="proj-1"
      docId="doc-1"
      docTitle="Roadmap"
      currentFolderId={currentFolderId}
      token="t"
      onSuccess={vi.fn()}
      onClose={vi.fn()}
    />
  )
}

describe('MoveDocDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getFolders as ReturnType<typeof vi.fn>).mockResolvedValue(FOLDERS)
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({})
  })

  it('moves a doc into a folder by folderId', async () => {
    renderDialog(null)
    await screen.findByText('Plans')

    fireEvent.click(screen.getByRole('radio', { name: /Plans/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Move' }))

    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/docs/doc-1',
        { folderId: 'f-plans' },
        't'
      )
    )
  })

  it('moves a doc out to the root with moveToRoot, not folderId: null', async () => {
    renderDialog('f-plans')
    await screen.findByText('Root (no folder)')

    fireEvent.click(screen.getByRole('radio', { name: /Root/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Move' }))

    // The API cannot tell folderId: null from an omitted folderId, so sending null would rename the
    // doc and silently leave it in the folder.
    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/docs/doc-1',
        { moveToRoot: true },
        't'
      )
    )
  })
})
