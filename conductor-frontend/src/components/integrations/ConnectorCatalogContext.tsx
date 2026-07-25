'use client';

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { listIntegrations, type IntegrationListItem } from '@/lib/api';

/**
 * Fetches the project's integrations catalog once per connector-detail-page view and shares it —
 * the breadcrumb (layout) and the generic overview (page body) both need this connector's catalog
 * entry, and without a shared fetch each would call `GET /integrations` independently.
 * `undefined` = loading, `null` = connectorId not found in the catalog.
 */
interface ConnectorCatalogValue {
  item: IntegrationListItem | null | undefined;
  refetch: () => Promise<void>;
}

const ConnectorCatalogContext = createContext<ConnectorCatalogValue | null>(null);

export function ConnectorCatalogProvider({
  projectId,
  connectorId,
  children,
}: {
  projectId: string;
  connectorId: string;
  children: ReactNode;
}) {
  const { accessToken } = useAuth();
  const [item, setItem] = useState<IntegrationListItem | null | undefined>(undefined);

  const refetch = useCallback(async () => {
    if (!accessToken) return;
    try {
      const all = await listIntegrations(projectId, accessToken);
      setItem(all.find((i) => i.connectorId === connectorId) ?? null);
    } catch (e) {
      console.error(e);
      setItem(null);
    }
  }, [projectId, connectorId, accessToken]);

  // Reset to the loading state on connectorId change — App Router reuses this provider across
  // sibling connector routes, so without this the previous connector's data would flash first.
  useEffect(() => { setItem(undefined); refetch(); }, [connectorId, refetch]);

  return (
    <ConnectorCatalogContext.Provider value={{ item, refetch }}>
      {children}
    </ConnectorCatalogContext.Provider>
  );
}

export function useConnectorCatalogItem(): ConnectorCatalogValue {
  const ctx = useContext(ConnectorCatalogContext);
  if (!ctx) throw new Error('useConnectorCatalogItem must be used within ConnectorCatalogProvider');
  return ctx;
}
