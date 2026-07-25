// Shared node-size constants for the automation diagram's step-level nodes and job frames — used by
// both the node renderers (StepNode/ConditionStepNode/TriggerNode/JobFrameNode) and the two-pass
// dagre layout (layout.ts), which needs these numbers to size job frames and space steps before any
// component ever mounts.

export const TRIGGER_W = 150
export const TRIGGER_H = 44

export const STEP_W = 190
export const STEP_H = 60

export const CONDITION_W = 130
export const CONDITION_H = 60

export const STEP_GAP_X = 40
export const JOB_GAP_X = 90
export const JOB_GAP_Y = 60

export const FRAME_HEADER_H = 32
export const FRAME_PADDING_X = 20
export const FRAME_PADDING_Y = 16
