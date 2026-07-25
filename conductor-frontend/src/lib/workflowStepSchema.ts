'use client'

// Live lookup of the automation step-type schema (GET /projects/{projectId}/workflows/step-schema),
// which mirrors StepSchemaRegistry — the backend's own registry of what WorkflowValidator accepts per
// step type. Used by the step detail panel to annotate a step's config fields with their live
// description/required-ness, so that copy never drifts from what the backend actually validates. Not
// project-specific data (same registry for every project), but the route stays project-scoped for
// auth consistency — see the endpoint's own doc comment on the backend.
//
// Cached at module scope, one entry per project, mirroring fetchWorkflowView's pattern in workflows.ts
// (concurrent callers share one in-flight request). No localStorage seeding — this is small, rarely
// needed before a user opens the detail panel, and never blocks first paint.

import { useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'
import type { WorkflowStepSchemaResponse } from '@/types/workflow'

const cache = new Map<string, WorkflowStepSchemaResponse>()
const inFlight = new Map<string, Promise<WorkflowStepSchemaResponse>>()

/** Fetch (and cache) the step-type schema for a project. Concurrent callers share one request. */
export function fetchWorkflowStepSchema(projectId: string, token: string): Promise<WorkflowStepSchemaResponse> {
  const cached = cache.get(projectId)
  if (cached) return Promise.resolve(cached)

  const pending = inFlight.get(projectId)
  if (pending) return pending

  const promise = apiGet<WorkflowStepSchemaResponse>(
    `/api/v1/projects/${projectId}/workflows/step-schema`,
    token,
  )
    .then((schema) => {
      cache.set(projectId, schema)
      return schema
    })
    .finally(() => {
      inFlight.delete(projectId)
    })

  inFlight.set(projectId, promise)
  return promise
}

/** React hook: resolve the step-type schema for a project, sharing the module cache. Returns
 * undefined until loaded (or if projectId/token aren't available yet) — callers should degrade
 * gracefully rather than block on it. */
export function useWorkflowStepSchema(
  projectId: string | undefined,
  token: string | null | undefined,
): WorkflowStepSchemaResponse | undefined {
  const [schema, setSchema] = useState<WorkflowStepSchemaResponse | undefined>(() =>
    projectId ? cache.get(projectId) : undefined,
  )

  useEffect(() => {
    if (!projectId || !token) return
    let cancelled = false
    fetchWorkflowStepSchema(projectId, token)
      .then((s) => {
        if (!cancelled) setSchema(s)
      })
      .catch(() => {
        /* non-fatal — the detail panel falls back to plain key/value display */
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  return schema
}
