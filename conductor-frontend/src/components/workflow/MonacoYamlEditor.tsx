'use client';

import dynamic from 'next/dynamic';
import { useRef } from 'react';
import { useTheme } from 'next-themes';

const Editor = dynamic(() => import('@monaco-editor/react').then(m => m.default), {
  ssr: false,
  loading: () => <div className="flex items-center justify-center h-full text-muted-foreground">Loading editor...</div>,
});

interface MonacoYamlEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
  /** Disables editing (still fully readable/selectable/copyable) — used by the Definition tab for
   *  viewers without workflow.manage. */
  readOnly?: boolean;
}

export default function MonacoYamlEditor({ value, onChange, height = '100%', readOnly = false }: MonacoYamlEditorProps) {
  const monacoRef = useRef<unknown>(null);
  const { resolvedTheme } = useTheme();

  function handleEditorWillMount(monaco: unknown) {
    monacoRef.current = monaco;
  }

  return (
    <Editor
      height={height}
      language="yaml"
      value={value}
      onChange={(val) => onChange(val ?? '')}
      beforeMount={handleEditorWillMount}
      options={{
        minimap: { enabled: false },
        fontSize: 13,
        lineNumbers: 'on',
        scrollBeyondLastLine: false,
        wordWrap: 'on',
        tabSize: 2,
        readOnly,
      }}
      // resolvedTheme is undefined pre-mount; default to dark (vs-dark) rather than flashing light.
      theme={resolvedTheme === 'light' ? 'vs' : 'vs-dark'}
    />
  );
}
