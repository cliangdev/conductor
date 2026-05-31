'use client'

import { useCallback, useEffect, useState } from 'react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { DocFolderDialog } from './DocFolderDialog'
import { MoveDocDialog } from './MoveDocDialog'
import { getFolders, getDocs, deleteFolder, deleteDoc } from '@/lib/docs-api'
import type { DocFolder, ProjectDocSummary } from '@/lib/docs-api'

interface DocTreeProps {
  projectId: string
  token: string
  userRole: string
  selectedDocId?: string
  onDocSelect: (docId: string) => void
  onRefresh?: () => void
}

type DialogState =
  | { mode: 'new-folder'; parentFolderId: string | null }
  | { mode: 'rename-folder'; folderId: string; currentName: string }
  | { mode: 'new-doc'; parentFolderId: string | null }
  | { mode: 'rename-doc'; docId: string; currentName: string }
  | { mode: 'move-doc'; docId: string; docTitle: string; folderId: string | null }

interface FolderNodeProps {
  folder: DocFolder
  allFolders: DocFolder[]
  projectId: string
  token: string
  userRole: string
  selectedDocId?: string
  onDocSelect: (docId: string) => void
  onOpenDialog: (state: DialogState) => void
  onRefresh: () => void
}

function FolderNode({
  folder,
  allFolders,
  projectId,
  token,
  userRole,
  selectedDocId,
  onDocSelect,
  onOpenDialog,
  onRefresh,
}: FolderNodeProps) {
  const [expanded, setExpanded] = useState(false)
  const [childDocs, setChildDocs] = useState<ProjectDocSummary[]>([])
  const [loadingChildren, setLoadingChildren] = useState(false)
  const [hovered, setHovered] = useState(false)
  const [dropdownOpen, setDropdownOpen] = useState(false)

  const childFolders = allFolders.filter((f) => f.parentId === folder.id)
  const canEdit = userRole === 'ADMIN' || userRole === 'CREATOR'

  async function handleToggle() {
    if (!expanded && childDocs.length === 0) {
      setLoadingChildren(true)
      try {
        const docs = await getDocs(projectId, folder.id, token)
        setChildDocs(docs)
      } catch {
        // Leave empty on error
      } finally {
        setLoadingChildren(false)
      }
    }
    setExpanded((prev) => !prev)
  }

  async function refreshChildren() {
    try {
      const docs = await getDocs(projectId, folder.id, token)
      setChildDocs(docs)
    } catch {
      // Leave unchanged on error
    }
    onRefresh()
  }

  async function handleDeleteFolder() {
    const confirmed = window.confirm('Delete folder and all its documents?')
    if (!confirmed) return
    try {
      await deleteFolder(projectId, folder.id, token)
      onRefresh()
    } catch {
      // Deletion failed silently
    }
  }

  return (
    <div>
      <div
        className="flex items-center gap-1 px-2 py-1 rounded-md text-sm text-foreground hover:bg-muted cursor-pointer select-none"
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        onClick={handleToggle}
      >
        <span className="text-xs text-muted-foreground w-3 shrink-0">
          {expanded ? '▼' : '▶'}
        </span>
        <span className="text-base leading-none mr-1">📁</span>
        <span className="flex-1 truncate">{folder.name}</span>
        {loadingChildren && (
          <span className="text-xs text-muted-foreground">...</span>
        )}
        {canEdit && (hovered || dropdownOpen) && (
          <DropdownMenu open={dropdownOpen} onOpenChange={setDropdownOpen}>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                onClick={(e) => e.stopPropagation()}
                className="ml-1 shrink-0 rounded px-1 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-accent-foreground focus:outline-none"
                aria-label="Folder actions"
              >
                ...
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
              <DropdownMenuItem
                className="cursor-pointer"
                onClick={() => onOpenDialog({ mode: 'new-doc', parentFolderId: folder.id })}
              >
                New doc
              </DropdownMenuItem>
              <DropdownMenuItem
                className="cursor-pointer"
                onClick={() => onOpenDialog({ mode: 'new-folder', parentFolderId: folder.id })}
              >
                New folder
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                className="cursor-pointer"
                onClick={() =>
                  onOpenDialog({ mode: 'rename-folder', folderId: folder.id, currentName: folder.name })
                }
              >
                Rename
              </DropdownMenuItem>
              <DropdownMenuItem
                className="cursor-pointer text-destructive focus:text-destructive"
                onClick={handleDeleteFolder}
              >
                Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>

      {expanded && (
        <div className="pl-4">
          {childFolders.map((child) => (
            <FolderNode
              key={child.id}
              folder={child}
              allFolders={allFolders}
              projectId={projectId}
              token={token}
              userRole={userRole}
              selectedDocId={selectedDocId}
              onDocSelect={onDocSelect}
              onOpenDialog={onOpenDialog}
              onRefresh={refreshChildren}
            />
          ))}
          {childDocs.map((doc) => (
            <DocLeaf
              key={doc.id}
              doc={doc}
              projectId={projectId}
              token={token}
              userRole={userRole}
              isSelected={doc.id === selectedDocId}
              onDocSelect={onDocSelect}
              onOpenDialog={onOpenDialog}
              onRefresh={refreshChildren}
            />
          ))}
        </div>
      )}
    </div>
  )
}

interface DocLeafProps {
  doc: ProjectDocSummary
  projectId: string
  token: string
  userRole: string
  isSelected: boolean
  onDocSelect: (docId: string) => void
  onOpenDialog: (state: DialogState) => void
  onRefresh: () => void
}

function DocLeaf({
  doc,
  projectId,
  token,
  userRole,
  isSelected,
  onDocSelect,
  onOpenDialog,
  onRefresh,
}: DocLeafProps) {
  const [hovered, setHovered] = useState(false)
  const [dropdownOpen, setDropdownOpen] = useState(false)
  const canEdit = userRole === 'ADMIN' || userRole === 'CREATOR'

  async function handleDelete() {
    const confirmed = window.confirm('Delete this document?')
    if (!confirmed) return
    try {
      await deleteDoc(projectId, doc.id, token)
      onRefresh()
    } catch {
      // Deletion failed silently
    }
  }

  return (
    <div
      className={
        'flex items-center gap-1 px-2 py-1 rounded-md text-sm cursor-pointer select-none ' +
        (isSelected
          ? 'bg-accent text-accent-foreground'
          : 'text-foreground hover:bg-muted')
      }
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onClick={() => onDocSelect(doc.id)}
    >
      <span className="w-3 shrink-0" />
      <span className="text-base leading-none mr-1">📄</span>
      <span className="flex-1 truncate">{doc.title}</span>
      {canEdit && (hovered || dropdownOpen) && (
        <DropdownMenu open={dropdownOpen} onOpenChange={setDropdownOpen}>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              onClick={(e) => e.stopPropagation()}
              className="ml-1 shrink-0 rounded px-1 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-accent-foreground focus:outline-none"
              aria-label="Document actions"
            >
              ...
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
            <DropdownMenuItem
              className="cursor-pointer"
              onClick={() => onOpenDialog({ mode: 'rename-doc', docId: doc.id, currentName: doc.title })}
            >
              Rename
            </DropdownMenuItem>
            <DropdownMenuItem
              className="cursor-pointer"
              onClick={() =>
                onOpenDialog({ mode: 'move-doc', docId: doc.id, docTitle: doc.title, folderId: doc.folderId })
              }
            >
              Move to...
            </DropdownMenuItem>
            <DropdownMenuItem
              className="cursor-pointer text-destructive focus:text-destructive"
              onClick={handleDelete}
            >
              Delete
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  )
}

export function DocTree({
  projectId,
  token,
  userRole,
  selectedDocId,
  onDocSelect,
  onRefresh,
}: DocTreeProps) {
  const [allFolders, setAllFolders] = useState<DocFolder[]>([])
  const [rootDocs, setRootDocs] = useState<ProjectDocSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [dialog, setDialog] = useState<DialogState | null>(null)
  const [rootDropdownOpen, setRootDropdownOpen] = useState(false)
  const canEdit = userRole === 'ADMIN' || userRole === 'CREATOR'

  const fetchTree = useCallback(async () => {
    if (!token) return
    try {
      const [folders, docs] = await Promise.all([
        getFolders(projectId, token),
        getDocs(projectId, null, token),
      ])
      setAllFolders(folders)
      setRootDocs(docs)
    } catch {
      // Leave unchanged on error
    } finally {
      setLoading(false)
    }
  }, [projectId, token])

  useEffect(() => {
    fetchTree()
  }, [fetchTree])

  function handleRefresh() {
    fetchTree()
    onRefresh?.()
  }

  const rootFolders = allFolders.filter((f) => f.parentId === null)

  if (loading) {
    return (
      <div className="px-3 py-2 text-xs text-muted-foreground">Loading...</div>
    )
  }

  return (
    <div className="py-2 select-none">
      <div className="flex items-center justify-between px-3 py-1 mb-1">
        <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Docs
        </span>
        {canEdit && (
          <DropdownMenu open={rootDropdownOpen} onOpenChange={setRootDropdownOpen}>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="rounded px-1 py-0.5 text-lg leading-none text-muted-foreground hover:bg-accent hover:text-accent-foreground focus:outline-none"
                aria-label="Add doc or folder"
              >
                +
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem
                className="cursor-pointer"
                onClick={() => setDialog({ mode: 'new-doc', parentFolderId: null })}
              >
                New doc
              </DropdownMenuItem>
              <DropdownMenuItem
                className="cursor-pointer"
                onClick={() => setDialog({ mode: 'new-folder', parentFolderId: null })}
              >
                New folder
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>

      <div className="space-y-0.5 px-1">
        {rootFolders.map((folder) => (
          <FolderNode
            key={folder.id}
            folder={folder}
            allFolders={allFolders}
            projectId={projectId}
            token={token}
            userRole={userRole}
            selectedDocId={selectedDocId}
            onDocSelect={onDocSelect}
            onOpenDialog={setDialog}
            onRefresh={handleRefresh}
          />
        ))}
        {rootDocs.map((doc) => (
          <DocLeaf
            key={doc.id}
            doc={doc}
            projectId={projectId}
            token={token}
            userRole={userRole}
            isSelected={doc.id === selectedDocId}
            onDocSelect={onDocSelect}
            onOpenDialog={setDialog}
            onRefresh={handleRefresh}
          />
        ))}
        {rootFolders.length === 0 && rootDocs.length === 0 && (
          <div className="px-2 py-1 text-xs text-muted-foreground">No docs yet</div>
        )}
      </div>

      {dialog && dialog.mode !== 'move-doc' && (
        <DocFolderDialog
          mode={dialog.mode}
          projectId={projectId}
          token={token}
          parentFolderId={
            dialog.mode === 'new-folder' || dialog.mode === 'new-doc'
              ? dialog.parentFolderId
              : undefined
          }
          folderId={dialog.mode === 'rename-folder' ? dialog.folderId : undefined}
          docId={dialog.mode === 'rename-doc' ? dialog.docId : undefined}
          currentName={
            dialog.mode === 'rename-folder' || dialog.mode === 'rename-doc'
              ? dialog.currentName
              : undefined
          }
          folders={dialog.mode === 'new-doc' ? allFolders : undefined}
          onSuccess={(newDocId) => {
            handleRefresh()
            if (newDocId) onDocSelect(newDocId)
          }}
          onClose={() => setDialog(null)}
        />
      )}

      {dialog && dialog.mode === 'move-doc' && (
        <MoveDocDialog
          projectId={projectId}
          docId={dialog.docId}
          docTitle={dialog.docTitle}
          currentFolderId={dialog.folderId}
          token={token}
          onSuccess={handleRefresh}
          onClose={() => setDialog(null)}
        />
      )}
    </div>
  )
}
