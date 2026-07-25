'use client';

import { useMemo, useState, useCallback } from 'react';
import type { Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { FlowCanvas } from '@/components/workflow/FlowCanvas';
import { Alert } from '@/components/ui/alert';
import { parseWorkflowYaml } from '@/lib/workflowAutomation';
import { layoutAutomationGraph } from '@/components/workflow/automation/layout';
import { buildAutomationGraph, type JobStatus, type JobRunStatus } from '@/components/workflow/automation/graphBuilder';
import { StepNode, ConditionStepNode, type StepNodeData } from '@/components/workflow/automation/StepNode';
import { TriggerNode } from '@/components/workflow/automation/TriggerNode';
import { JobFrameNode } from '@/components/workflow/automation/JobFrameNode';
import { StepDetailPanel } from '@/components/workflow/automation/StepDetailPanel';
import type { WorkflowStepRunDto } from '@/types/workflow';

export type { JobStatus, JobRunStatus };

const nodeTypes = {
  trigger: TriggerNode,
  step: StepNode,
  condition: ConditionStepNode,
  jobFrame: JobFrameNode,
} as const;

interface WorkflowDiagramProps {
  yaml: string;
  jobStatuses?: Record<string, JobStatus>;
  jobRunData?: Record<string, JobRunStatus>;
  stepRunData?: Record<string, WorkflowStepRunDto>;
  runId?: string;
  projectId?: string;
  token?: string | null;
}

export default function WorkflowDiagram({
  yaml, jobStatuses, jobRunData, stepRunData, runId, projectId, token,
}: WorkflowDiagramProps) {
  const [selected, setSelected] = useState<StepNodeData | null>(null);

  const result = useMemo(() => {
    try {
      const parsed = parseWorkflowYaml(yaml);
      const layout = layoutAutomationGraph(parsed);
      const { nodes, edges } = buildAutomationGraph(parsed, layout, jobStatuses, jobRunData, stepRunData);
      return { nodes, edges, error: null };
    } catch (e) {
      return { nodes: [], edges: [], error: e instanceof Error ? e.message : 'Failed to render diagram' };
    }
  }, [yaml, jobStatuses, jobRunData, stepRunData]);

  const handleNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
    if (node.type !== 'step' && node.type !== 'condition') return;
    setSelected(node.data as StepNodeData);
  }, []);

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

  return (
    <>
      <FlowCanvas
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        interactive
        onNodeClick={handleNodeClick}
        minimap
      />
      <StepDetailPanel
        open={selected !== null}
        onOpenChange={(open) => { if (!open) setSelected(null); }}
        step={selected?.step ?? null}
        runData={selected?.runData}
        runId={runId}
        projectId={projectId}
        token={token}
      />
    </>
  );
}
