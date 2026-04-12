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
