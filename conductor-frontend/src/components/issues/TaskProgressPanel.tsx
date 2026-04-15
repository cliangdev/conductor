'use client'

import { useEffect, useState } from 'react'
import { ChevronDown, ChevronRight, CheckCircle2, CheckSquare2, Square } from 'lucide-react'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import { Badge } from '@/components/ui/badge'

interface Task {
  id: string
  title: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'BLOCKED'
  [key: string]: unknown
}

interface Epic {
  id: string
  title: string
  tasks: Task[]
  [key: string]: unknown
}

interface TasksData {
  epics: Epic[]
  [key: string]: unknown
}

interface TaskProgressPanelProps {
  issueId: string
  projectId: string
}

function countCompleted(tasks: Task[]): number {
  return tasks.filter((t) => t.status === 'COMPLETED').length
}

function ProgressBar({ percentage }: { percentage: number }) {
  return (
    <div
      role="progressbar"
      aria-valuenow={percentage}
      aria-valuemin={0}
      aria-valuemax={100}
      className="w-full h-1.5 bg-muted rounded-full overflow-hidden"
    >
      <div
        className="h-full bg-primary rounded-full transition-all"
        style={{ width: `${percentage}%` }}
      />
    </div>
  )
}

export function TaskProgressPanel({ issueId, projectId }: TaskProgressPanelProps) {
  const { accessToken } = useAuth()
  const [tasksData, setTasksData] = useState<TasksData | null>(null)
  const [expanded, setExpanded] = useState(true)
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    if (!accessToken) return

    apiGet<TasksData>(
      `/api/v1/projects/${projectId}/issues/${issueId}/tasks`,
      accessToken
    )
      .then((data) => {
        if (data.epics && data.epics.length > 0) {
          setTasksData(data)
        }
        setLoaded(true)
      })
      .catch((err: unknown) => {
        // Render nothing on 404 or any error
        setLoaded(true)
      })
  }, [accessToken, projectId, issueId])

  if (!loaded || !tasksData) return null

  const allTasks = tasksData.epics.flatMap((e) => e.tasks)
  const totalTasks = allTasks.length
  const completedTasks = countCompleted(allTasks)
  const percentage = totalTasks > 0 ? Math.round((completedTasks / totalTasks) * 100) : 0

  return (
    <div className="border-t border-border">
      <div className="px-4 py-3">
        <button
          onClick={() => setExpanded((prev) => !prev)}
          className="flex items-center justify-between w-full group"
          aria-expanded={expanded}
        >
          <span className="text-xs font-semibold text-foreground-subtle uppercase tracking-wide">
            Implementation Progress
          </span>
          {expanded ? (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
          )}
        </button>

        {!expanded && (
          <div className="mt-2">
            <ProgressBar percentage={percentage} />
          </div>
        )}

        {expanded && (
          <div className="mt-2 space-y-3">
            <div>
              <p className="text-xs text-muted-foreground mb-1.5">
                {completedTasks} / {totalTasks} tasks complete
              </p>
              <ProgressBar percentage={percentage} />
            </div>

            <div className="space-y-3">
              {tasksData.epics.map((epic) => {
                const epicCompleted = countCompleted(epic.tasks)
                const epicTotal = epic.tasks.length
                const allEpicDone = epicTotal > 0 && epicCompleted === epicTotal

                return (
                  <div key={epic.id}>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-xs font-medium text-foreground truncate mr-2">
                        {epic.title}
                      </span>
                      <div className="flex items-center gap-1 shrink-0">
                        <span className="text-xs text-muted-foreground">
                          {epicCompleted}/{epicTotal}
                        </span>
                        {allEpicDone && (
                          <CheckCircle2 className="h-3.5 w-3.5 text-green-500" />
                        )}
                      </div>
                    </div>

                    <ul className="space-y-1">
                      {epic.tasks.map((task) => (
                        <li key={task.id} className="flex items-center gap-2">
                          {task.status === 'COMPLETED' ? (
                            <CheckSquare2 className="h-3.5 w-3.5 text-green-500 shrink-0" />
                          ) : (
                            <Square className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                          )}
                          <span className="text-xs text-foreground flex-1 truncate">
                            {task.title}
                          </span>
                          {task.status === 'BLOCKED' && (
                            <Badge variant="destructive" className="text-[10px] px-1.5 py-0 shrink-0">
                              BLOCKED
                            </Badge>
                          )}
                        </li>
                      ))}
                    </ul>
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
