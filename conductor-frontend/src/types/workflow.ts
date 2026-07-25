export type WorkflowState = 'DRAFT' | 'PUBLISHED' | 'DISABLED';

/**
 * Explicit, server-derived kind of a Workflow. Use this to distinguish lifecycle (statechart) from
 * automation (YAML) workflows — never infer it from the shape of `definition`. (COND-22)
 */
export type WorkflowKind = 'LIFECYCLE' | 'AUTOMATION';

export interface WorkflowDefinitionDto {
  id: string;
  projectId: string;
  name: string;
  /** Statechart slug (definition.id) for lifecycle workflows; absent for automations. (COND-22) */
  slug?: string;
  /** Display noun (singular, server default applied) for lifecycle workflows; absent for automations. (COND-22) */
  noun?: string;
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
  /** Work items bound to this workflow (any version); 0 (or absent) for automations. */
  workItemCount?: number;
  schemaVersion?: number;
  /**
   * The versioned statechart (statuses, transitions, reviews, steps, …). Non-null only for
   * lifecycle workflows — this is how a lifecycle workflow is distinguished from a YAML automation.
   */
  definition?: Record<string, unknown> | null;
  warnings?: WorkflowValidationWarning[];
  createdAt: string;
  updatedAt: string;
  /** Consecutive FAILED run completions since the last SUCCESS (or since last re-enabled). */
  consecutiveFailures?: number;
  /**
   * Set when WorkflowFailureCircuitBreaker auto-disabled this workflow after repeated failures —
   * distinguishes "the system paused this" from a human unchecking `enabled`. Re-enabling clears it.
   */
  autoPausedAt?: string;
  /** Why autoPausedAt is set — a free-form code (only "CONSECUTIVE_FAILURES" today). */
  autoPauseReason?: string;
  /** The run that tripped the circuit breaker, so the UI can link straight to the failure. */
  autoPausedRunId?: string;
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
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLING' | 'CANCELLED';
  startedAt: string;
  completedAt?: string;
}

export interface WorkflowStepRunDto {
  id: string;
  stepId?: string;
  stepName: string;
  stepType: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'CANCELLED';
  log?: string;
  outputJson?: string;
  errorReason?: string;
  explanation?: string;
  remediation?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface WorkflowJobRunDto {
  id: string;
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'LOOP_EXHAUSTED' | 'CANCELLED';
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
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLING' | 'CANCELLED';
  startedAt: string;
  completedAt?: string;
  jobs: WorkflowJobRunDto[];
}

/** GET /projects/{projectId}/workflows/step-schema — registry-driven, see StepSchemaRegistry (backend). */
export interface StepFieldSchemaDto {
  name: string;
  type: 'STRING' | 'INTEGER' | 'BOOLEAN' | 'OBJECT' | 'ARRAY' | 'MAP';
  required: boolean;
  description: string;
  constraints?: string | null;
}

export interface StepTypeSchemaDto {
  type: string;
  description: string;
  fields: StepFieldSchemaDto[];
}

export interface InterpolationRootDto {
  name: string;
  description: string;
}

export interface InterpolationFunctionDto {
  name: string;
  description: string;
}

export interface InterpolationSchemaDto {
  roots: InterpolationRootDto[];
  functions: InterpolationFunctionDto[];
}

export interface WorkflowStepSchemaResponse {
  stepTypes: StepTypeSchemaDto[];
  interpolation: InterpolationSchemaDto;
}
