'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import { Button } from '@/components/ui/button';

const MonacoYamlEditor = dynamic(() => import('./MonacoYamlEditor'), { ssr: false });
const WorkflowDiagram = dynamic(() => import('./WorkflowDiagram'), { ssr: false });

interface WorkflowEditorLayoutProps {
  title: string;
  initialYaml: string;
  initialName?: string;
  onSave: (name: string, yaml: string) => Promise<void>;
  onDiscard: () => void;
  saving: boolean;
  error: string | null;
}

export default function WorkflowEditorLayout({
  title,
  initialYaml,
  initialName = '',
  onSave,
  onDiscard,
  saving,
  error,
}: WorkflowEditorLayoutProps) {
  const [yaml, setYaml] = useState(initialYaml);
  const [name, setName] = useState(initialName);

  const handleSave = () => {
    const workflowName = name || extractNameFromYaml(yaml);
    onSave(workflowName, yaml);
  };

  return (
    <div className="flex flex-col h-screen">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b bg-background">
        <h1 className="text-lg font-semibold">{title}</h1>
        <div className="flex items-center gap-2">
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

      {/* Split pane */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: Monaco editor */}
        <div className="w-1/2 border-r overflow-hidden">
          <MonacoYamlEditor value={yaml} onChange={setYaml} />
        </div>

        {/* Right: Mermaid diagram */}
        <div className="w-1/2 overflow-hidden bg-muted/20">
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
