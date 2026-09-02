'use client';

import { CheckCircleIcon } from 'lucide-react';
import { ConnectorIcon } from '@/components/integrations/ConnectorIcon';
import { StatusBadge } from '@/components/ui/status-badge';
import { Button } from '@/components/ui/button';
import { timeAgo } from '@/lib/format';
import type { ConnectionSummary } from '@/lib/api';

/**
 * A connection's health is not its status. `status` is what the workspace asked for ("connected");
 * `healthStatus` is what the platform said last time we used those credentials. An expired or
 * revoked account therefore stays ACTIVE and merely reports UNHEALTHY — which is precisely the case
 * this row has to make visible, so a publish doesn't just quietly fail later.
 */
export type ConnectionWithHealth = ConnectionSummary & {
  healthCheckedAt?: string | null;
  healthMessage?: string | null;
};

export function isUnhealthy(connection: ConnectionWithHealth): boolean {
  return connection.healthStatus === 'UNHEALTHY';
}

interface ConnectionRowProps {
  connection: ConnectionWithHealth;
  connectorId: string;
  /** Falls back to the connector's name when the connection carries no label of its own. */
  connectorName: string;
  /** The connector's short text badge, shown when it has no logo asset. */
  iconLabel: string;
  canMutate?: boolean;
  disconnecting?: boolean;
  onDisconnect?: (connectionId: string) => void;
}

/** One connected instance of a connector, with the error state an unhealthy connection needs. */
export function ConnectionRow({
  connection,
  connectorId,
  connectorName,
  iconLabel,
  canMutate = false,
  disconnecting = false,
  onDisconnect,
}: ConnectionRowProps) {
  const unhealthy = isUnhealthy(connection);

  return (
    <div className="bg-card rounded-lg border border-border p-4 flex items-center gap-4">
      <ConnectorIcon connectorId={connectorId} iconLabel={iconLabel} className="h-8 w-8" />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-sm font-medium text-foreground truncate">
            {connection.label || connectorName}
          </span>
          {/* "ERROR" only selects the red hue from the one status ramp; the label is what a user
              reads, and it names the fix rather than the diagnosis. */}
          {unhealthy && <StatusBadge status="ERROR" label="Needs reconnect" />}
        </div>
        {unhealthy ? (
          <div className="text-xs text-status-failed mt-0.5">
            {connection.healthMessage ||
              "The platform rejected this connection's credentials. Reconnect the account."}
            {connection.healthCheckedAt && (
              <span className="text-muted-foreground"> · {timeAgo(connection.healthCheckedAt)}</span>
            )}
          </div>
        ) : (
          <div className="text-xs text-muted-foreground flex items-center gap-1">
            {connection.status === 'ACTIVE' ? (
              <>
                <CheckCircleIcon className="h-3.5 w-3.5 text-status-done" />
                Connected
              </>
            ) : (
              connection.status
            )}
          </div>
        )}
      </div>
      {canMutate && onDisconnect && (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          onClick={() => onDisconnect(connection.id)}
          disabled={disconnecting}
          className="text-destructive hover:text-destructive hover:bg-destructive/10"
        >
          {disconnecting ? 'Disconnecting…' : 'Disconnect'}
        </Button>
      )}
    </div>
  );
}
