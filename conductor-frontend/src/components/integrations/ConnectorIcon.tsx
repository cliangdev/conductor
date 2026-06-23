'use client';

import { useState } from 'react';

/**
 * Renders a connector's brand logo from `/public/integrations/{connectorId}.svg`, falling back to
 * the `iconLabel` text badge (the historical behavior) when no asset exists or the image fails to
 * load. `connectorId` is the lookup key — adding a new logo is just dropping an SVG in that folder.
 */
export function ConnectorIcon({
  connectorId,
  iconLabel,
  className = 'h-10 w-10',
}: {
  connectorId: string;
  iconLabel: string;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);

  return (
    <div
      className={`${className} rounded-md bg-muted flex items-center justify-center text-sm font-bold text-foreground flex-shrink-0 overflow-hidden`}
    >
      {failed ? (
        iconLabel
      ) : (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={`/integrations/${connectorId}.svg`}
          alt={`${iconLabel} logo`}
          className="h-full w-full object-contain p-1.5"
          onError={() => setFailed(true)}
        />
      )}
    </div>
  );
}
