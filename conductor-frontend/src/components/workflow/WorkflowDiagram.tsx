'use client';

import { useMemo } from 'react';
import { ReactFlow, ReactFlowProvider, Background, useReactFlow, Handle, Position, type Node, type Edge } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from 'dagre';

// ── Node dimensions ────────────────────────────────────────────────────────────
const TRIGGER_W = 160;
const TRIGGER_H = 44;
const JOB_W = 200;
const JOB_H = 64;
const CONDITION_W = 120;
const CONDITION_H = 60;

// ── Status colours ─────────────────────────────────────────────────────────────
type JobStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING' | 'LOOP_EXHAUSTED';

const statusStyles: Record<JobStatus, string> = {
  SUCCESS:       'bg-green-500 text-white border-green-600',
  FAILED:        'bg-red-500 text-white border-red-600',
  RUNNING:       'bg-yellow-400 text-gray-900 border-yellow-500',
  SKIPPED:       'bg-gray-300 text-gray-600 border-gray-400',
  PENDING:       'bg-gray-100 text-gray-500 border-gray-300',
  LOOP_EXHAUSTED:'bg-orange-400 text-white border-orange-500',
};

// ── Custom node: Trigger ───────────────────────────────────────────────────────
function TriggerNode({ data }: { data: { label: string } }) {
  return (
    <div className="px-4 py-2 rounded-full bg-indigo-100 text-indigo-700 border border-indigo-300 text-xs font-semibold text-center shadow-sm whitespace-pre-line leading-tight">
      {data.label}
      <Handle type="source" position={Position.Bottom} className="!bg-indigo-400" />
    </div>
  );
}

// ── Custom node: Job ───────────────────────────────────────────────────────────
function JobNode({ data }: { data: { label: string; stepInfo: string; status?: JobStatus } }) {
  const style = statusStyles[data.status ?? 'PENDING'];
  return (
    <div className={`rounded-lg border px-3 py-2 text-xs shadow-sm flex flex-col gap-0.5 ${style}`}
         style={{ width: JOB_W }}>
      <Handle type="target" position={Position.Top} className="!bg-gray-400" />
      <span className="font-semibold truncate">{data.label}</span>
      {data.stepInfo && (
        <span className="opacity-75 truncate">{data.stepInfo}</span>
      )}
      <Handle type="source" position={Position.Bottom} className="!bg-gray-400" />
    </div>
  );
}

// ── Custom node: Condition (diamond shape) ─────────────────────────────────────
function ConditionNode({ data }: { data: { label: string; status?: JobStatus } }) {
  const style = statusStyles[data.status ?? 'PENDING'];
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
      <Handle type="target" position={Position.Top} className="!bg-gray-400" />
      <Handle type="source" position={Position.Bottom} id="true" className="!bg-green-400" style={{ left: '25%' }} />
      <Handle type="source" position={Position.Bottom} id="false" className="!bg-red-400" style={{ left: '75%' }} />
    </div>
  );
}

// ── Zoom controls ──────────────────────────────────────────────────────────────
// Rendered as an absolute sibling of <ReactFlow> (not inside Panel) so the
// container's overflow-hidden never clips the buttons.
function ZoomControls() {
  const { zoomIn, zoomOut } = useReactFlow();
  return (
    <div className="absolute top-4 right-4 z-10 flex flex-col overflow-hidden rounded-lg border border-gray-300 bg-white shadow-md">
      <button
        onClick={() => zoomIn()}
        className="flex h-9 w-9 items-center justify-center border-b border-gray-300 text-lg font-medium text-gray-700 hover:bg-gray-100 active:bg-gray-200"
        aria-label="Zoom in"
      >
        +
      </button>
      <button
        onClick={() => zoomOut()}
        className="flex h-9 w-9 items-center justify-center text-lg font-medium text-gray-700 hover:bg-gray-100 active:bg-gray-200"
        aria-label="Zoom out"
      >
        −
      </button>
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
  const edges: Edge[] = [];

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
          label: '↺ loop',
          type: 'default',
          style: { strokeDasharray: '4 2' },
        });
      }

      const needs = job['needs'];
      const needsList: string[] = needs
        ? (Array.isArray(needs) ? needs.map(String) : [String(needs)])
        : [];

      if (needsList.length === 0) {
        edges.push({ id: `__trigger__->${jobId}`, source: '__trigger__', target: jobId });
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
      <div className="flex items-center justify-center h-full p-4 text-sm text-amber-600 bg-amber-50 rounded">
        {result.error}
      </div>
    );
  }

  const { nodes, edges } = result;

  if (!nodes.length) {
    return (
      <div className="flex items-center justify-center h-full p-4 text-sm text-gray-400">
        No workflow defined yet
      </div>
    );
  }

  return (
    <ReactFlowProvider>
      <div className="relative h-full w-full rounded-md">
        {/* ReactFlow gets its own overflow-hidden so it clips internally */}
        <div className="absolute inset-0 overflow-hidden rounded-md">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            proOptions={{ hideAttribution: true }}
          >
            <Background color="#e5e7eb" gap={16} />
          </ReactFlow>
        </div>
        {/* Controls sit outside the overflow-hidden layer — never clipped */}
        <ZoomControls />
      </div>
    </ReactFlowProvider>
  );
}
