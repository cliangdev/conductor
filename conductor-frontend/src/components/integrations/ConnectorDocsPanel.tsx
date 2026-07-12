'use client';

import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import { CONNECTOR_DOCS } from './docs';

export default function ConnectorDocsPanel({ connectorId }: { connectorId: string }) {
  const content = CONNECTOR_DOCS[connectorId];

  if (!content) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <p className="text-sm text-muted-foreground">No documentation available for this integration.</p>
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <MarkdownRenderer content={content} />
    </div>
  );
}
