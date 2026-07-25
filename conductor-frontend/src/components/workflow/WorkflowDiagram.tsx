'use client';

import { useMemo } from 'react';
import '@xyflow/react/dist/style.css';
import { FlowCanvas } from '@/components/workflow/FlowCanvas';
import { Alert } from '@/components/ui/alert';
import { parseWorkflowYaml } from '@/lib/workflowAutomation';
import { layoutAutomationGraph } from '@/components/workflow/automation/layout';
import { buildAutomationGraph, type JobStatus, type JobRunStatus } from '@/components/workflow/automation/graphBuilder';
import { StepNode, ConditionStepNode } from '@/components/workflow/automation/StepNode';
import { TriggerNode } from '@/components/workflow/automation/TriggerNode';
import { JobFrameNode } from '@/components/workflow/automation/JobFrameNode';

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
}

export default function WorkflowDiagram({ yaml, jobStatuses, jobRunData }: WorkflowDiagramProps) {
  const result = useMemo(() => {
    try {
      const parsed = parseWorkflowYaml(yaml);
      const layout = layoutAutomationGraph(parsed);
      const { nodes, edges } = buildAutomationGraph(parsed, layout, jobStatuses, jobRunData);
      return { nodes, edges, error: null };
    } catch (e) {
      return { nodes: [], edges: [], error: e instanceof Error ? e.message : 'Failed to render diagram' };
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
