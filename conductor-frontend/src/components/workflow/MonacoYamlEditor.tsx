'use client';

import dynamic from 'next/dynamic';
import { useRef } from 'react';

const Editor = dynamic(() => import('@monaco-editor/react').then(m => m.default), {
  ssr: false,
  loading: () => <div className="flex items-center justify-center h-full text-muted-foreground">Loading editor...</div>,
});

interface MonacoYamlEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
}

export default function MonacoYamlEditor({ value, onChange, height = '100%' }: MonacoYamlEditorProps) {
  const monacoRef = useRef<unknown>(null);

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
      }}
      theme="vs-dark"
    />
  );
}
