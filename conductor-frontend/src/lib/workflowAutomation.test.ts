import { describe, it, expect } from 'vitest'
import { parseWorkflowYaml, resolveStepKind, isManualTrigger } from '@/lib/workflowAutomation'

describe('resolveStepKind', () => {
  it('resolves uses: docker://... to docker', () => {
    expect(resolveStepKind({ uses: 'docker://python:3.12' })).toBe('docker')
  })

  it('resolves bare uses: values to themselves', () => {
    expect(resolveStepKind({ uses: 'integration' })).toBe('integration')
    expect(resolveStepKind({ uses: 'agent' })).toBe('agent')
    expect(resolveStepKind({ uses: 'claude-code' })).toBe('claude-code')
    expect(resolveStepKind({ uses: 'action' })).toBe('action')
  })

  it('falls back to explicit type: when there is no uses:', () => {
    expect(resolveStepKind({ type: 'http' })).toBe('http')
    expect(resolveStepKind({ type: 'kestra' })).toBe('kestra')
    expect(resolveStepKind({ type: 'condition' })).toBe('condition')
  })

  it('defaults to http when neither uses: nor type: is a recognized kind', () => {
    expect(resolveStepKind({})).toBe('http')
    expect(resolveStepKind({ type: 'nonsense' })).toBe('http')
  })
})

describe('parseWorkflowYaml', () => {
  it('returns empty result for blank yaml', () => {
    expect(parseWorkflowYaml('')).toEqual({ triggers: [], jobs: [] })
    expect(parseWorkflowYaml('   ')).toEqual({ triggers: [], jobs: [] })
  })

  it('throws on invalid yaml', () => {
    expect(() => parseWorkflowYaml('jobs: [unterminated')).toThrow('Invalid YAML')
  })

  it('parses all five trigger kinds', () => {
    const yaml = `
on:
  workflow_dispatch: {}
  webhook: {}
  conductor.work_item.status_changed:
    filters:
      status: DONE
  github.pull_request:
    types: [opened, labeled]
  schedule:
    cron: "0 9 * * 1"
jobs: {}
`
    const { triggers } = parseWorkflowYaml(yaml)
    const kinds = triggers.map(t => t.kind)
    expect(kinds).toEqual([
      'workflow_dispatch', 'webhook', 'work_item_status_changed', 'github_pull_request', 'schedule',
    ])
    const schedule = triggers.find(t => t.kind === 'schedule')
    expect(schedule?.cron).toBe('0 9 * * 1')
  })

  it('parses all eight step kinds within a job', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  everything:
    steps:
      - id: a
        type: http
        url: https://example.com
      - id: b
        uses: docker://python:3.12
      - id: c
        type: kestra
      - id: d
        uses: integration
        with:
          connector: gsc
      - id: e
        uses: action
        with:
          connector: discord
      - id: f
        uses: agent
        with:
          agent: researcher
      - id: g
        uses: claude-code
      - id: h
        type: condition
        expression: '\${{ steps.a.outputs.ok }}'
        then: success_job
        else: failure_job
`
    const { jobs } = parseWorkflowYaml(yaml)
    expect(jobs).toHaveLength(1)
    const kinds = jobs[0].steps.map(s => s.kind)
    expect(kinds).toEqual(['http', 'docker', 'kestra', 'integration', 'action', 'agent', 'claude-code', 'condition'])

    const condition = jobs[0].steps[7]
    expect(condition.then).toBe('success_job')
    expect(condition.else).toBe('failure_job')
    expect(condition.expression).toBe('${{ steps.a.outputs.ok }}')
  })

  it('parses job needs, runs-on, if, and loop', () => {
    const yaml = `
on:
  schedule:
    cron: "* * * * *"
jobs:
  poll:
    runs-on: self-hosted
    loop:
      until: '\${{ steps.check.outputs.done }}'
      max_iterations: 10
    steps:
      - type: http
        url: https://example.com
  notify:
    needs: poll
    if: '\${{ needs.poll.outputs.found }}'
    steps:
      - type: http
        url: https://example.com
`
    const { jobs } = parseWorkflowYaml(yaml)
    const poll = jobs.find(j => j.jobId === 'poll')!
    expect(poll.runsOn).toBe('self-hosted')
    expect(poll.loop?.maxIterations).toBe(10)

    const notify = jobs.find(j => j.jobId === 'notify')!
    expect(notify.needs).toEqual(['poll'])
    expect(notify.ifExpr).toBe('${{ needs.poll.outputs.found }}')
  })

  it('normalizes a needs: list with multiple dependencies', () => {
    const yaml = `
on:
  workflow_dispatch: {}
jobs:
  a: { steps: [] }
  b: { steps: [] }
  c:
    needs: [a, b]
    steps: []
`
    const { jobs } = parseWorkflowYaml(yaml)
    expect(jobs.find(j => j.jobId === 'c')!.needs).toEqual(['a', 'b'])
  })

  it('defaults workflow_dispatch.manual to true when omitted', () => {
    const { triggers } = parseWorkflowYaml('on:\n  workflow_dispatch: {}\njobs: {}\n')
    expect(triggers[0].manual).toBe(true)
  })

  it('reads workflow_dispatch.manual: false for system-dispatched workflows', () => {
    const { triggers } = parseWorkflowYaml('on:\n  workflow_dispatch:\n    manual: false\njobs: {}\n')
    expect(triggers[0].manual).toBe(false)
  })
})

describe('isManualTrigger', () => {
  it('is true for workflow_dispatch triggers that omit manual or set it true', () => {
    expect(isManualTrigger({ kind: 'workflow_dispatch', raw: {}, manual: true })).toBe(true)
    expect(isManualTrigger({ kind: 'workflow_dispatch', raw: {} })).toBe(true)
  })

  it('is false for workflow_dispatch triggers with manual: false', () => {
    expect(isManualTrigger({ kind: 'workflow_dispatch', raw: {}, manual: false })).toBe(false)
  })

  it('is always true for non-workflow_dispatch trigger kinds', () => {
    expect(isManualTrigger({ kind: 'schedule', raw: {} })).toBe(true)
  })
})
