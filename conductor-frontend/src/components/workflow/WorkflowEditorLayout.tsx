'use client';

import dynamic from 'next/dynamic';
import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ResizableSplit } from '@/components/ui/ResizableSplit';

const MonacoYamlEditor = dynamic(() => import('./MonacoYamlEditor'), { ssr: false });
const WorkflowDiagram = dynamic(() => import('./WorkflowDiagram'), { ssr: false });

// Same per-pane floor ResizableSplit's other caller (DocEditor) already uses — proven safe there.
const SPLIT_MIN_PANE = 240;
// Below this measured *container* width, two SPLIT_MIN_PANE panes plus the drag handle can't both
// fit, and ResizableSplit's pixel clamp (`Math.max(min, Math.min(clientWidth - min, ...))`) breaks
// down: it pins the left pane at `min` even when `clientWidth < 2*min`, squeezing the right pane
// below `min` (or negative). Gating on *measured container width* rather than the `md` viewport
// breakpoint matters here specifically because the persistent sidebar (`Sidebar.tsx`, ~280px at
// md+) plus PageContainer's gutters can leave the actual content column under 2*SPLIT_MIN_PANE even
// on a "desktop" viewport right around 768px — a real case, not just a hypothetical.
const STACK_BELOW_PX = SPLIT_MIN_PANE * 2 + 40;

/** Whether the given element is currently wide enough for the two-pane split; re-measured on
 *  resize via ResizeObserver (same pattern `ResizableSplit` itself uses). Starts `true` (side-by-
 *  side) so first paint doesn't flash for the common desktop case — corrected before Monaco/the
 *  diagram (both `dynamic(..., { ssr: false })`) render anything meaningful. */
function useCanSplit(containerRef: React.RefObject<HTMLDivElement | null>): boolean {
  const [canSplit, setCanSplit] = useState(true);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const measure = () => setCanSplit(el.clientWidth >= STACK_BELOW_PX);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [containerRef]);

  return canSplit;
}

interface WorkflowEditorLayoutProps {
  initialYaml: string;
  initialName?: string;
  onSave: (name: string, yaml: string) => Promise<void>;
  onDiscard: () => void;
  saving: boolean;
  error: string | null;
  /** When true, the editor is contained (for use inside a detail tab) instead of full-screen. */
  embedded?: boolean;
  /** Read-only: hides Save/Discard and the name input, and puts Monaco in read-only mode. Used by
   *  the Definition tab for viewers without `workflow.manage` — they can still read the YAML and
   *  see the diagram, just not edit either. */
  readOnly?: boolean;
}

export default function WorkflowEditorLayout({
  initialYaml,
  initialName = '',
  onSave,
  onDiscard,
  saving,
  error,
  embedded = false,
  readOnly = false,
}: WorkflowEditorLayoutProps) {
  const [yaml, setYaml] = useState(initialYaml);
  const [name, setName] = useState(initialName);
  const splitContainerRef = useRef<HTMLDivElement>(null);
  const canSplit = useCanSplit(splitContainerRef);

  const handleSave = () => {
    const workflowName = name || extractNameFromYaml(yaml);
    onSave(workflowName, yaml);
  };

  const editor = <MonacoYamlEditor value={yaml} onChange={setYaml} readOnly={readOnly} />;
  const diagram = (
    <div className="h-full bg-muted/20">
      <WorkflowDiagram yaml={yaml} />
    </div>
  );

  return (
    <div className={embedded ? 'flex flex-col h-[calc(100vh-260px)] min-h-[520px] border rounded-lg overflow-hidden' : 'flex flex-col h-screen'}>
      {/* Actions — no title here; both callers render their own PageHeader above this component. */}
      <div className="flex flex-wrap items-center justify-end gap-3 px-4 py-3 border-b bg-background">
        {readOnly ? (
          <span className="text-sm text-muted-foreground">
            Read-only — you don&apos;t have permission to edit this workflow.
          </span>
        ) : (
          <div className="flex items-center gap-2 shrink-0">
            {error && <span className="text-sm text-destructive">{error}</span>}
            <Button variant="outline" onClick={onDiscard} disabled={saving}>Discard</Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Saving...' : 'Save'}
            </Button>
          </div>
        )}
      </div>

      {/* Name input — shown whenever editing is allowed (both /new and an existing workflow, fixing
          the earlier gap where renaming was only possible on /new because this was gated on
          `!initialName` instead of on edit permission). Hidden entirely in read-only mode. */}
      {!readOnly && (
        <div className="px-4 py-2 border-b">
          <Label htmlFor="workflow-name" className="mb-1">Name</Label>
          <Input
            id="workflow-name"
            placeholder="Workflow name (or set in YAML)"
            value={name}
            onChange={e => setName(e.target.value)}
          />
        </div>
      )}

      {/* Split pane: Monaco editor (or read-only YAML view) + live diagram — side-by-side and
          resizable when there's room, stacked vertically otherwise (narrow viewport, or a
          "desktop" viewport where the sidebar has eaten most of the width — see useCanSplit). */}
      <div ref={splitContainerRef} className="flex flex-1 overflow-hidden">
        {canSplit ? (
          <ResizableSplit
            storageKey="workflow_editor_pane_split"
            defaultFraction={0.5}
            min={SPLIT_MIN_PANE}
            left={editor}
            right={diagram}
          />
        ) : (
          <div className="flex flex-1 flex-col overflow-hidden">
            <div className="flex-1 min-h-0 border-b overflow-hidden">{editor}</div>
            <div className="flex-1 min-h-0 overflow-hidden">{diagram}</div>
          </div>
        )}
      </div>
    </div>
  );
}

function extractNameFromYaml(yaml: string): string {
  const match = yaml.match(/^name:\s*(.+)$/m);
  return match ? match[1].trim() : 'untitled';
}
