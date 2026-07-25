'use client';

import { useMemo } from 'react';
import { Handle, Position, MarkerType, type Node, type Edge } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from 'dagre';
import { FlowCanvas } from '@/components/workflow/FlowCanvas';
import { statusHueClasses } from '@/components/ui/status-badge';
import { statusHue } from '@/lib/workflows';
import { Alert } from '@/components/ui/alert';

// ── Node dimensions ────────────────────────────────────────────────────────────
const TRIGGER_W = 160;
const TRIGGER_H = 44;
const JOB_W = 200;
const JOB_H = 64;
const CONDITION_W = 120;
const CONDITION_H = 60;

// ── Status colours ─────────────────────────────────────────────────────────────
type JobStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING' | 'LOOP_EXHAUSTED';

const ALL_JOB_STATUSES: JobStatus[] = ['SUCCESS', 'FAILED', 'RUNNING', 'SKIPPED', 'PENDING', 'LOOP_EXHAUSTED'];

// Built once at module load rather than re-running the statusHue/statusHueClasses pipeline on every render.
const NODE_STATUS_CLASSES: Record<JobStatus, string> = Object.fromEntries(
  ALL_JOB_STATUSES.map((status) => {
    const c = statusHueClasses(statusHue(status));
    return [status, `${c.bg} ${c.text} ${c.border}`];
  }),
) as Record<JobStatus, string>;

function nodeStatusClasses(status?: JobStatus): string {
  return NODE_STATUS_CLASSES[status ?? 'PENDING'];
}

// ── Custom node: Trigger ───────────────────────────────────────────────────────
function TriggerNode({ data }: { data: { label: string } }) {
  return (
    <div className="px-4 py-2 rounded-full bg-accent-soft text-primary border border-primary/30 text-xs font-semibold text-center shadow-sm whitespace-pre-line leading-tight">
      {data.label}
      <Handle type="source" position={Position.Bottom} className="!bg-primary" />
    </div>
  );
}

// ── Custom node: Job ───────────────────────────────────────────────────────────
function JobNode({ data }: { data: { label: string; stepInfo: string; status?: JobStatus } }) {
  const style = nodeStatusClasses(data.status);
  return (
    <div className={`rounded-lg border px-3 py-2 text-xs shadow-sm flex flex-col gap-0.5 ${style}`}
         style={{ width: JOB_W }}>
      <Handle type="target" position={Position.Top} className="!bg-foreground-subtle" />
      <span className="font-semibold truncate">{data.label}</span>
      {data.stepInfo && (
        <span className="opacity-75 truncate">{data.stepInfo}</span>
      )}
      <Handle type="source" position={Position.Bottom} className="!bg-foreground-subtle" />
    </div>
  );
}

// ── Custom node: Condition (diamond shape) ─────────────────────────────────────
function ConditionNode({ data }: { data: { label: string; status?: JobStatus } }) {
  const style = nodeStatusClasses(data.status);
  return (
    <div className="relative flex items-center justify-center" style={{ width: CONDITION_W, height: CONDITION_H }}>
      <div
        className={`absolute inset-0 rounded border-2 ${style}`}
        style={{ transform: 'rotate(45deg)', transformOrigin: 'center' }}
      />
      <span className="relative z-10 text-xs font-semibold text-center px-1 truncate max-w-full"
            style={{ fontSize: 10 }}>
        {data.label}
      </span>
      <Handle type="target" position={Position.Top} className="!bg-foreground-subtle" />
      <Handle type="source" position={Position.Bottom} id="true" className="!bg-status-done" style={{ left: '25%' }} />
      <Handle type="source" position={Position.Bottom} id="false" className="!bg-status-failed" style={{ left: '75%' }} />
    </div>
  );
}

const nodeTypes = { trigger: TriggerNode, job: JobNode, condition: ConditionNode } as const;

// ── Dagre layout ───────────────────────────────────────────────────────────────
function applyDagreLayout(nodes: Node[], edges: Edge[]): Node[] {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 70 });

  nodes.forEach(n => {
    let w: number, h: number;
    if (n.type === 'trigger') { w = TRIGGER_W; h = TRIGGER_H; }
    else if (n.type === 'condition') { w = CONDITION_W; h = CONDITION_H; }
    else { w = JOB_W; h = JOB_H; }
    g.setNode(n.id, { width: w, height: h });
  });
  // Only set non-self edges for layout
  edges.forEach(e => {
    if (e.source !== e.target) g.setEdge(e.source, e.target);
  });
  dagre.layout(g);

  return nodes.map(n => {
    const { x, y } = g.node(n.id);
    let w: number, h: number;
    if (n.type === 'trigger') { w = TRIGGER_W; h = TRIGGER_H; }
    else if (n.type === 'condition') { w = CONDITION_W; h = CONDITION_H; }
    else { w = JOB_W; h = JOB_H; }
    return { ...n, position: { x: x - w / 2, y: y - h / 2 } };
  });
}

// ── Richer job run data for run detail page ────────────────────────────────────
interface JobRunStatus {
  status: JobStatus;
  iteration?: number;
  maxIterations?: number;
}

// ── Graph builder ──────────────────────────────────────────────────────────────
function buildFlowGraph(
  yamlText: string,
  jobStatuses?: Record<string, JobStatus>,
  jobRunData?: Record<string, JobRunStatus>
): { nodes: Node[]; edges: Edge[] } {
  const empty = { nodes: [], edges: [] };
  if (!yamlText.trim()) return empty;

  let parsed: unknown;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const jsYaml = require('js-yaml') as typeof import('js-yaml');
    parsed = jsYaml.load(yamlText);
  } catch {
    throw new Error('Invalid YAML');
  }

  if (!parsed || typeof parsed !== 'object') return empty;
  const workflow = parsed as Record<string, unknown>;

  const nodes: Node[] = [];
  const edges: (Edge & { pathOptions?: { borderRadius?: number } })[] = [];

  // Trigger node
  const onBlock = workflow['on'];
  const triggerLabel = onBlock && typeof onBlock === 'object'
    ? Object.keys(onBlock as object).join('\n')
    : 'trigger';
  nodes.push({ id: '__trigger__', type: 'trigger', position: { x: 0, y: 0 }, data: { label: triggerLabel } });

  // Job nodes + edges
  const jobsBlock = workflow['jobs'];
  if (jobsBlock && typeof jobsBlock === 'object') {
    const jobs = jobsBlock as Record<string, unknown>;
    for (const jobId of Object.keys(jobs)) {
      const job = jobs[jobId] as Record<string, unknown>;
      const steps = (job['steps'] as unknown[]) ?? [];
      const stepCount = steps.length;
      const typedSteps = steps.filter((s): s is Record<string, unknown> => typeof s === 'object' && s !== null);

      const stepTypes = [...new Set(
        typedSteps.map(s => s['type'] as string).filter(Boolean)
      )];

      // Detect docker steps (uses: docker://...)
      const hasDockerStep = typedSteps.some(s => {
        const uses = s['uses'] as string | undefined;
        return uses && uses.startsWith('docker://');
      });

      let stepInfo = stepCount
        ? `${stepCount} step${stepCount !== 1 ? 's' : ''}${stepTypes.length ? ' · ' + stepTypes.join(', ') : ''}`
        : '';
      if (hasDockerStep) stepInfo += ' [docker]';

      // Detect if this is a condition job (last step has type: condition)
      const lastStep = typedSteps[typedSteps.length - 1];
      const isConditionJob = lastStep?.['type'] === 'condition';

      // Detect loop job
      const loopBlock = job['loop'] as Record<string, unknown> | undefined;
      const isLoopJob = !!loopBlock;
      const maxIterations = loopBlock ? Number(loopBlock['max_iterations']) : undefined;

      // Determine status
      const runData = jobRunData?.[jobId];
      const status = runData?.status ?? jobStatuses?.[jobId];

      // Build node label — show iteration annotation for loop jobs with run data
      let nodeLabel = jobId;
      if (isLoopJob && runData?.iteration !== undefined) {
        const iterDisplay = (runData.iteration ?? 0);
        const maxDisplay = runData.maxIterations ?? maxIterations;
        nodeLabel = maxDisplay !== undefined
          ? `${jobId} · ${iterDisplay}/${maxDisplay}`
          : `${jobId} · ${iterDisplay}`;
      }

      nodes.push({
        id: jobId,
        type: isConditionJob ? 'condition' : 'job',
        position: { x: 0, y: 0 },
        data: {
          label: nodeLabel,
          stepInfo,
          status,
        },
      });

      // Self-loop edge for loop jobs
      if (isLoopJob) {
        edges.push({
          id: `${jobId}->self-loop`,
          source: jobId,
          target: jobId,
          label: 'loop',
          type: 'smoothstep',
          pathOptions: { borderRadius: 8 },
          style: { strokeDasharray: '4 2' },
          markerEnd: { type: MarkerType.ArrowClosed },
        });
      }

      const needs = job['needs'];
      const needsList: string[] = needs
        ? (Array.isArray(needs) ? needs.map(String) : [String(needs)])
        : [];

      if (needsList.length === 0) {
        edges.push({
          id: `__trigger__->${jobId}`,
          source: '__trigger__',
          target: jobId,
          type: 'smoothstep',
          pathOptions: { borderRadius: 8 },
          markerEnd: { type: MarkerType.ArrowClosed },
        });
      } else {
        for (const dep of needsList) {
          const ifCond = job['if'] as string | undefined;
          const label = ifCond
            ? 'if: ' + ifCond.replace(/\$\{\{|\}\}/g, '').trim().slice(0, 40)
            : undefined;
          edges.push({
            id: `${dep}->${jobId}`,
            source: dep,
            target: jobId,
            label,
            labelStyle: { fontSize: 10 },
            type: 'smoothstep',
            pathOptions: { borderRadius: 8 },
            markerEnd: { type: MarkerType.ArrowClosed },
          });
        }
      }

      // Condition edges: then/else
      if (isConditionJob) {
        const thenJob = lastStep['then'] as string | undefined;
        const elseJob = lastStep['else'] as string | undefined;
        if (thenJob) {
          edges.push({
            id: `${jobId}->then-${thenJob}`,
            source: jobId,
            target: thenJob,
            sourceHandle: 'true',
            label: 'true',
            labelStyle: { fontSize: 10 },
            type: 'smoothstep',
            pathOptions: { borderRadius: 8 },
            markerEnd: { type: MarkerType.ArrowClosed },
          });
        }
        if (elseJob) {
          edges.push({
            id: `${jobId}->else-${elseJob}`,
            source: jobId,
            target: elseJob,
            sourceHandle: 'false',
            label: 'false',
            labelStyle: { fontSize: 10 },
            type: 'smoothstep',
            pathOptions: { borderRadius: 8 },
            markerEnd: { type: MarkerType.ArrowClosed },
          });
        }
      }
    }
  }

  const laidOut = applyDagreLayout(nodes, edges);
  return { nodes: laidOut, edges };
}

// ── Component ──────────────────────────────────────────────────────────────────
interface WorkflowDiagramProps {
  yaml: string;
  jobStatuses?: Record<string, JobStatus>;
  jobRunData?: Record<string, JobRunStatus>;
}

export default function WorkflowDiagram({ yaml, jobStatuses, jobRunData }: WorkflowDiagramProps) {
  const result = useMemo(() => {
    try {
      return { ...buildFlowGraph(yaml, jobStatuses, jobRunData), error: null };
    } catch (e) {
      return { nodes: [] as Node[], edges: [] as Edge[], error: e instanceof Error ? e.message : 'Failed to render diagram' };
    }
  }, [yaml, jobStatuses, jobRunData]);

  if (result.error) {
    return (
      <div className="flex items-center justify-center h-full p-4">
        <Alert variant="warning">{result.error}</Alert>
      </div>
    );
  }

  const { nodes, edges } = result;

  if (!nodes.length) {
    return (
      <div className="flex items-center justify-center h-full p-4 text-sm text-muted-foreground">
        No workflow defined yet
      </div>
    );
  }

  return <FlowCanvas nodes={nodes} edges={edges} nodeTypes={nodeTypes} />;
}
