export interface WorkflowDefinitionDto {
  id: string;
  projectId: string;
  name: string;
  yaml: string;
  enabled: boolean;
  webhookToken?: string;
  warnings?: WorkflowValidationWarning[];
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowValidationWarning {
  message: string;
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
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
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
