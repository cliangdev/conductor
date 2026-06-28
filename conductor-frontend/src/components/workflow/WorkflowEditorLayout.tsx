'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Breadcrumb, type Crumb } from '@/components/layout/PageHeader';

const MonacoYamlEditor = dynamic(() => import('./MonacoYamlEditor'), { ssr: false });
const WorkflowDiagram = dynamic(() => import('./WorkflowDiagram'), { ssr: false });

interface WorkflowEditorLayoutProps {
  title: string;
  breadcrumbs?: Crumb[];
  initialYaml: string;
  initialName?: string;
  onSave: (name: string, yaml: string) => Promise<void>;
  onDiscard: () => void;
  saving: boolean;
  error: string | null;
  /** When true, the editor is contained (for use inside a detail tab) instead of full-screen. */
  embedded?: boolean;
}

export default function WorkflowEditorLayout({
  title,
  breadcrumbs,
  initialYaml,
  initialName = '',
  onSave,
  onDiscard,
  saving,
  error,
  embedded = false,
}: WorkflowEditorLayoutProps) {
  const [yaml, setYaml] = useState(initialYaml);
  const [name, setName] = useState(initialName);

  const handleSave = () => {
    const workflowName = name || extractNameFromYaml(yaml);
    onSave(workflowName, yaml);
  };

  return (
    <div className={embedded ? 'flex flex-col h-[70vh] border rounded-lg overflow-hidden' : 'flex flex-col h-screen'}>
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 border-b bg-background">
        <div className="min-w-0">
          {breadcrumbs && breadcrumbs.length > 0 && <Breadcrumb items={breadcrumbs} className="mb-0.5" />}
          <h1 className="text-lg font-semibold truncate">{title}</h1>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {error && <span className="text-sm text-destructive">{error}</span>}
          <Button variant="outline" onClick={onDiscard} disabled={saving}>Discard</Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? 'Saving...' : 'Save'}
          </Button>
        </div>
      </div>

      {/* Name input — only shown for new workflows (no initialName) */}
      {!initialName && (
        <div className="px-4 py-2 border-b">
          <input
            className="w-full px-3 py-1.5 text-sm border rounded-md bg-background"
            placeholder="Workflow name (or set in YAML)"
            value={name}
            onChange={e => setName(e.target.value)}
          />
        </div>
      )}

      {/* Split pane — stacks vertically on mobile, side-by-side on md+ */}
      <div className="flex flex-1 flex-col md:flex-row overflow-hidden">
        {/* Editor */}
        <div className="flex-1 min-h-0 md:w-1/2 border-b md:border-b-0 md:border-r overflow-hidden">
          <MonacoYamlEditor value={yaml} onChange={setYaml} />
        </div>

        {/* Diagram */}
        <div className="flex-1 min-h-0 md:w-1/2 overflow-hidden bg-muted/20">
          <WorkflowDiagram yaml={yaml} />
        </div>
      </div>
    </div>
  );
}

function extractNameFromYaml(yaml: string): string {
  const match = yaml.match(/^name:\s*(.+)$/m);
  return match ? match[1].trim() : 'untitled';
}
