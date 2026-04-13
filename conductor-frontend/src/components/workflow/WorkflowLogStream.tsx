'use client';

import { useEffect, useRef, useState } from 'react';

interface WorkflowLogStreamProps {
  runId: string;
  isRunning: boolean;
  staticLog?: string;
}

export function WorkflowLogStream({ runId, isRunning, staticLog }: WorkflowLogStreamProps) {
  const [lines, setLines] = useState<string[]>([]);
  const [completed, setCompleted] = useState(!isRunning);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isRunning) {
      if (staticLog) setLines(staticLog.split('\n'));
      return;
    }

    const apiUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const es = new EventSource(`${apiUrl}/api/v1/workflow-runs/${runId}/logs/stream`, {
      withCredentials: true,
    });

    es.addEventListener('log-chunk', (e) => {
      const data = JSON.parse((e as MessageEvent).data);
      setLines(prev => [...prev, ...(data.lines as string[])]);
    });

    es.addEventListener('run-complete', () => {
      setCompleted(true);
      es.close();
    });

    es.onerror = () => {
      es.close();
    };

    return () => {
      es.close();
    };
  }, [runId, isRunning, staticLog]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [lines]);

  return (
    <div className="bg-black text-green-400 font-mono text-xs rounded-lg p-3 h-48 overflow-y-auto">
      {lines.length === 0 && !completed && (
        <span className="text-gray-500">Waiting for logs...</span>
      )}
      {lines.map((line, i) => (
        <div key={i}>{line}</div>
      ))}
      <div ref={bottomRef} />
    </div>
  );
}
