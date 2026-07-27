import { describe, it, expect } from 'vitest'
import { layoutPipelineGraph, nodeHeight, NODE_W } from './pipelineLayout'
import type { PipelineStageHealth, PipelineStageEdge } from '@/lib/knowledge-api'

function stage(overrides: Partial<PipelineStageHealth> = {}): PipelineStageHealth {
  return { stage: 'WEBHOOKS', label: 'Webhooks', counts: { pending: 0 }, ...overrides }
}

const REAL_EDGES: PipelineStageEdge[] = [
  { from: 'WEBHOOKS', to: 'INBOX' },
  { from: 'FEEDS', to: 'DIGESTS' },
  { from: 'DIGESTS', to: 'INBOX' },
  { from: 'INBOX', to: 'LIBRARIAN_RUNS' },
  { from: 'LIBRARIAN_RUNS', to: 'PAGES_WRITTEN' },
]

const ALL_STAGES: PipelineStageHealth[] = [
  stage({ stage: 'WEBHOOKS', counts: { pending: 0, processed: 3, failed: 0, dead: 0 } }),
  stage({ stage: 'FEEDS', label: 'Connector feeds', counts: { active: 2, paused: 0, setupRequired: 0, dead: 0, stale: 0 } }),
  stage({ stage: 'DIGESTS', label: 'Metrics digests', counts: { pending: 0, narrating: 0, submitted: 1, skipped: 12, dead: 0 } }),
  stage({ stage: 'INBOX', label: 'Knowledge inbox', counts: { pending: 1, processing: 0, processed: 5, dead: 0 } }),
  stage({ stage: 'LIBRARIAN_RUNS', label: 'Librarian runs', counts: { pending: 0, running: 0, success: 4 } }),
  stage({ stage: 'PAGES_WRITTEN', label: 'Pages written (30d)', counts: { written: 7 } }),
]

function yOf(nodes: ReturnType<typeof layoutPipelineGraph>['nodes'], stageId: string) {
  const node = nodes.find((n) => n.stage === stageId)
  if (!node) throw new Error(`no node for stage ${stageId}`)
  return node.y
}

describe('layoutPipelineGraph', () => {
  it('places INBOX below both of its fan-in sources, and the tail strictly below INBOX', () => {
    const { nodes } = layoutPipelineGraph(ALL_STAGES, REAL_EDGES)

    expect(yOf(nodes, 'INBOX')).toBeGreaterThan(yOf(nodes, 'WEBHOOKS'))
    expect(yOf(nodes, 'INBOX')).toBeGreaterThan(yOf(nodes, 'DIGESTS'))
    expect(yOf(nodes, 'DIGESTS')).toBeGreaterThan(yOf(nodes, 'FEEDS'))
    expect(yOf(nodes, 'LIBRARIAN_RUNS')).toBeGreaterThan(yOf(nodes, 'INBOX'))
    expect(yOf(nodes, 'PAGES_WRITTEN')).toBeGreaterThan(yOf(nodes, 'LIBRARIAN_RUNS'))
  })

  it('gives every node the fixed NODE_W', () => {
    const { nodes } = layoutPipelineGraph(ALL_STAGES, REAL_EDGES)
    expect(nodes.every((n) => n.width === NODE_W)).toBe(true)
  })

  it('drops an edge whose endpoint stage is absent from the response', () => {
    const stages = [
      stage({ stage: 'FEEDS', label: 'Connector feeds', counts: { active: 1 } }),
      stage({ stage: 'DIGESTS', label: 'Metrics digests', counts: { pending: 0 } }),
    ]
    const { edges } = layoutPipelineGraph(stages, REAL_EDGES)

    expect(edges).toHaveLength(1)
    expect(edges[0]).toMatchObject({ from: 'FEEDS', to: 'DIGESTS' })
  })

  it('gives a stage with more count buckets a taller computed height', () => {
    const fourBuckets = stage({ counts: { a: 0, b: 0, c: 0, d: 0 } })
    const oneBucket = stage({ counts: { a: 0 } })
    expect(nodeHeight(fourBuckets)).toBeGreaterThan(nodeHeight(oneBucket))
  })

  it('handles an empty stage list without throwing', () => {
    const { nodes, edges } = layoutPipelineGraph([], REAL_EDGES)
    expect(nodes).toEqual([])
    expect(edges).toEqual([])
  })
})
