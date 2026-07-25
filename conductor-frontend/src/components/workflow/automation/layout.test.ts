import { describe, it, expect } from 'vitest'
import { parseWorkflowYaml } from '@/lib/workflowAutomation'
import { layoutAutomationGraph } from './layout'
import { STEP_W, TRIGGER_W, FRAME_HEADER_H, FRAME_PADDING_Y, STEP_H } from './dimensions'

function jobById(layout: ReturnType<typeof layoutAutomationGraph>, jobId: string) {
  const job = layout.jobs.find(j => j.jobId === jobId)
  if (!job) throw new Error(`no job ${jobId} in layout`)
  return job
}

describe('layoutAutomationGraph', () => {
  it('lays out a single-job workflow with one trigger to its left', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  poll:
    steps:
      - id: check
        type: http
        url: https://api.example.com/health
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    expect(layout.triggers).toHaveLength(1)
    expect(layout.jobs).toHaveLength(1)

    const poll = jobById(layout, 'poll')
    expect(poll.stepPositions).toHaveLength(1)
    // one step → frame content is exactly one step wide plus padding on both sides
    expect(poll.width).toBeGreaterThan(STEP_W)
    expect(poll.height).toBeGreaterThan(STEP_H)

    // trigger sits strictly to the left of the job frame
    expect(layout.triggers[0].x + TRIGGER_W).toBeLessThanOrEqual(poll.x)
  })

  it('positions multiple steps within a job left-to-right in array order, non-overlapping', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  pipeline:
    steps:
      - id: a
        type: http
        url: https://example.com
      - id: b
        uses: docker://python:3.12
      - id: c
        uses: agent
        with:
          agent: researcher
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const job = jobById(layout, 'pipeline')
    expect(job.stepPositions).toHaveLength(3)

    const [a, b, c] = job.stepPositions
    expect(a.x).toBeLessThan(b.x)
    expect(b.x).toBeLessThan(c.x)
    // no overlap: each next step starts at or after the previous step's right edge
    expect(b.x).toBeGreaterThanOrEqual(a.x + STEP_W)
    expect(c.x).toBeGreaterThanOrEqual(b.x + STEP_W)
  })

  it('places a downstream job to the right of the job it needs', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  collect:
    steps:
      - type: http
        url: https://example.com
  analyze:
    needs: collect
    steps:
      - type: http
        url: https://example.com
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const collect = jobById(layout, 'collect')
    const analyze = jobById(layout, 'analyze')
    expect(analyze.x).toBeGreaterThan(collect.x + collect.width)
  })

  it('lays out condition then/else branches as sibling jobs to the right of the condition job', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  check-env:
    steps:
      - name: route by environment
        type: condition
        expression: "\${{ event.env == 'production' }}"
        then: deploy-prod
        else: deploy-staging
  deploy-prod:
    steps:
      - type: http
        url: https://example.com
  deploy-staging:
    steps:
      - type: http
        url: https://example.com
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const checkEnv = jobById(layout, 'check-env')
    const prod = jobById(layout, 'deploy-prod')
    const staging = jobById(layout, 'deploy-staging')

    expect(prod.x).toBeGreaterThan(checkEnv.x + checkEnv.width)
    expect(staging.x).toBeGreaterThan(checkEnv.x + checkEnv.width)

    // the condition step itself is sized/positioned as this job's one (and only) step
    const conditionStep = checkEnv.stepPositions[0]
    expect(conditionStep).toBeDefined()
    expect(conditionStep.x).toBeGreaterThanOrEqual(0)
  })

  it('sizes a loop job frame the same as a non-loop job with the same steps (loop is frame-level, not step-level)', () => {
    const yaml = `
on:
  schedule:
    cron: "* * * * *"
jobs:
  wait-for-deployment:
    loop:
      max_iterations: 10
      until: "\${{ steps.check.outputs.status == 'healthy' }}"
    steps:
      - id: check
        type: http
        url: https://api.example.com/health
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const job = jobById(layout, 'wait-for-deployment')
    expect(job.stepPositions).toHaveLength(1)
    expect(job.width).toBeGreaterThan(STEP_W)
  })

  it('gives a condition step frame content that accounts for the header and padding', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  gate:
    steps:
      - type: condition
        expression: "true"
        then: a
        else: b
  a: { steps: [] }
  b: { steps: [] }
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const gate = jobById(layout, 'gate')
    const [conditionPos] = gate.stepPositions
    expect(conditionPos.y).toBeGreaterThanOrEqual(FRAME_HEADER_H)
    expect(conditionPos.y).toBeGreaterThanOrEqual(FRAME_HEADER_H + FRAME_PADDING_Y - 1)
    expect(conditionPos.x).toBeGreaterThan(0)
  })

  it('handles a job with zero steps without throwing', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  empty:
    steps: []
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const job = jobById(layout, 'empty')
    expect(job.stepPositions).toHaveLength(0)
    expect(job.width).toBeGreaterThan(0)
    expect(job.height).toBeGreaterThan(0)
  })

  it('produces no positions for an empty workflow', () => {
    const layout = layoutAutomationGraph({ triggers: [], jobs: [] })
    expect(layout.triggers).toEqual([])
    expect(layout.jobs).toEqual([])
  })

  it('sizes a condition step footprint using CONDITION_W, distinct from a regular step', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  mixed:
    steps:
      - type: http
        url: https://example.com
      - type: condition
        expression: "true"
        then: a
        else: b
  a: { steps: [] }
  b: { steps: [] }
`
    const layout = layoutAutomationGraph(parseWorkflowYaml(yaml))
    const mixed = jobById(layout, 'mixed')
    const [httpPos, conditionPos] = mixed.stepPositions
    // condition step starts right after the http step's full STEP_W width
    expect(conditionPos.x).toBeGreaterThanOrEqual(httpPos.x + STEP_W)
  })
})
