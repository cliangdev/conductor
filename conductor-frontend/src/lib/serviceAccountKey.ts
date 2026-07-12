/**
 * Parse/validate a pasted or uploaded GCP service-account JSON key, and extract the fields the
 * integrations UI needs to prefill sibling config (e.g. gcpProjectId). Pure + unit-testable —
 * the key JSON itself is only ever held client-side in component state, never persisted here.
 */
export interface ParsedServiceAccountKey {
  valid: boolean;
  error?: string;
  projectId?: string;
  clientEmail?: string;
}

export function parseServiceAccountKey(text: string): ParsedServiceAccountKey {
  if (!text || !text.trim()) {
    return { valid: false, error: 'Paste or upload a service account JSON key.' };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { valid: false, error: 'Not valid JSON.' };
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return { valid: false, error: 'Not valid JSON.' };
  }

  const obj = parsed as Record<string, unknown>;
  if (obj.type !== 'service_account') {
    return { valid: false, error: 'Not a service account key (expected type: "service_account").' };
  }

  const projectId = obj.project_id;
  if (typeof projectId !== 'string' || !projectId) {
    return { valid: false, error: 'Key is missing project_id.' };
  }

  const clientEmail = typeof obj.client_email === 'string' ? obj.client_email : undefined;

  return { valid: true, projectId, clientEmail };
}
