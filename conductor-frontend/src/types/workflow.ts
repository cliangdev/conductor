export type WorkflowState = 'DRAFT' | 'PUBLISHED';

/**
 * Explicit, server-derived kind of a Workflow. Use this to distinguish lifecycle (statechart) from
 * automation (YAML) workflows — never infer it from the shape of `definition`. (COND-22)
 */
export type WorkflowKind = 'LIFECYCLE' | 'AUTOMATION';

export interface WorkflowDefinitionDto {
  id: string;
  projectId: string;
  name: string;
  /**
   * Legacy automation source. Present for YAML automation workflows; absent for COND-18 lifecycle
   * (statechart) workflows, which carry `definition` instead.
   */
  yaml?: string;
  enabled: boolean;
  /** Authoritative discriminator — LIFECYCLE (statechart) vs AUTOMATION (YAML). (COND-22) */
  kind?: WorkflowKind;
  /** Whether this lifecycle Workflow is shown as a sidebar nav entry. (COND-22) */
  sidebarEnabled?: boolean;
  webhookToken?: string;
  /** Monotonic version; in-flight Work Items pin to their version. (COND-18) */
  version?: number;
  /** Lifecycle state of the definition. Only PUBLISHED is bindable by Work Items. (COND-18) */
  state?: WorkflowState;
  /** Nav-grouping slug; single-Workflow Areas render flat. (COND-18) */
  area?: string;
  schemaVersion?: number;
  /**
   * The versioned statechart (statuses, transitions, reviews, steps, …). Non-null only for
   * lifecycle workflows — this is how a lifecycle workflow is distinguished from a YAML automation.
   */
  definition?: Record<string, unknown> | null;
  warnings?: WorkflowValidationWarning[];
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowValidationWarning {
  message: string;
}

/** Response envelope from create/update — the saved workflow plus any non-fatal validation warnings. */
export interface WorkflowCreateResponse {
  workflow: WorkflowDefinitionDto;
  warnings?: WorkflowValidationWarning[];
}

export interface WorkflowRunDto {
  id: string;
  workflowId: string;
  triggerType: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  startedAt: string;
  completedAt?: string;
}

export interface WorkflowStepRunDto {
  id: string;
  stepId?: string;
  stepName: string;
  stepType: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  log?: string;
  outputJson?: string;
  errorReason?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface WorkflowJobRunDto {
  id: string;
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'LOOP_EXHAUSTED';
  iteration?: number;
  startedAt?: string;
  completedAt?: string;
  steps: WorkflowStepRunDto[];
}

export interface WorkflowRunDetailDto {
  id: string;
  workflowId: string;
  workflowYaml: string;
  triggerType: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  startedAt: string;
  completedAt?: string;
  jobs: WorkflowJobRunDto[];
}
