'use client';

import { useEffect, useRef, useState } from 'react';

async function generateMermaidFromYaml(
  yamlText: string,
  jobStatuses?: Record<string, 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING'>
): Promise<string> {
  if (!yamlText.trim()) return '';

  const jsYaml = await import('js-yaml');

  let parsed: unknown;
  try {
    parsed = jsYaml.load(yamlText);
  } catch {
    throw new Error('Invalid YAML');
  }

  if (!parsed || typeof parsed !== 'object') return '';
  const workflow = parsed as Record<string, unknown>;

  const lines: string[] = ['flowchart TD'];

  const onBlock = workflow['on'];
  if (onBlock && typeof onBlock === 'object') {
    const triggers = Object.keys(onBlock as object);
    const triggerLabel = triggers.join('\\n');
    lines.push(`  T(["${triggerLabel}"])`);
  } else {
    lines.push(`  T(["trigger"])`);
  }

  const jobsBlock = workflow['jobs'];
  if (!jobsBlock || typeof jobsBlock !== 'object') {
    return lines.join('\n');
  }

  const jobs = jobsBlock as Record<string, unknown>;
  const jobIds = Object.keys(jobs);

  for (const jobId of jobIds) {
    const job = jobs[jobId] as Record<string, unknown>;
    const needs = job['needs'];
    if (!needs || (Array.isArray(needs) && needs.length === 0)) {
      lines.push(`  T --> ${jobId}`);
    }
  }

  for (const jobId of jobIds) {
    const job = jobs[jobId] as Record<string, unknown>;
    const steps = (job['steps'] as unknown[]) ?? [];
    const stepCount = steps.length;
    const stepTypes = [...new Set(steps
      .filter((s): s is Record<string, unknown> => typeof s === 'object' && s !== null)
      .map(s => s['type'] as string)
      .filter(Boolean)
    )].join(', ');
    const label = stepTypes
      ? `${jobId}\\n${stepCount} step${stepCount !== 1 ? 's' : ''} · ${stepTypes}`
      : jobId;
    lines.push(`  ${jobId}["${label}"]`);

    const needs = job['needs'];
    const needsList: string[] = needs
      ? (Array.isArray(needs) ? needs.map(String) : [String(needs)])
      : [];

    for (const dep of needsList) {
      const ifCond = job['if'] as string | undefined;
      if (ifCond) {
        const shortCond = ifCond.replace(/\$\{\{|\}\}/g, '').trim().slice(0, 40);
        lines.push(`  ${dep} -->|"if: ${shortCond}"| ${jobId}`);
      } else {
        lines.push(`  ${dep} --> ${jobId}`);
      }
    }
  }

  if (jobStatuses) {
    for (const [jobId, status] of Object.entries(jobStatuses)) {
      const color = ({
        SUCCESS: '#22c55e',
        FAILED: '#ef4444',
        RUNNING: '#eab308',
        SKIPPED: '#9ca3af',
        PENDING: '#d1d5db',
      } as Record<string, string>)[status] ?? '#d1d5db';
      lines.push(`  style ${jobId} fill:${color},color:#fff`);
    }
  }

  return lines.join('\n');
}

interface WorkflowDiagramProps {
  yaml: string;
  jobStatuses?: Record<string, 'SUCCESS' | 'FAILED' | 'RUNNING' | 'SKIPPED' | 'PENDING'>;
}

export default function WorkflowDiagram({ yaml, jobStatuses }: WorkflowDiagramProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(null);
  const renderIdRef = useRef(0);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      const renderId = ++renderIdRef.current;
      try {
        setError(null);
        const definition = await generateMermaidFromYaml(yaml, jobStatuses);
        if (!definition || renderId !== renderIdRef.current) return;

        const mermaid = (await import('mermaid')).default;
        mermaid.initialize({ startOnLoad: false, theme: 'default' });

        const { svg } = await mermaid.render(`diagram-${renderId}`, definition);
        if (renderId === renderIdRef.current && containerRef.current) {
          containerRef.current.innerHTML = svg;
        }
      } catch (e) {
        if (renderId === renderIdRef.current) {
          setError(e instanceof Error ? e.message : 'Failed to render diagram');
        }
      }
    }, 300);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [yaml, jobStatuses]);

  if (error) {
    return (
      <div className="flex items-center justify-center h-full p-4 text-sm text-amber-600 bg-amber-50 rounded">
        {error}
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="h-full overflow-auto flex items-start justify-center p-4"
    />
  );
}
