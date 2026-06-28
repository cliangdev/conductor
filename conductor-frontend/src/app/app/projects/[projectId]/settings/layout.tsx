'use client';

import { useParams, usePathname } from 'next/navigation';
import { PageContainer } from '@/components/layout/PageContainer';
import { Breadcrumb } from '@/components/layout/PageHeader';

// Sub-page labels, mirroring the Settings sub-nav in the Sidebar. Workspace-scoped
// only — Workflows / Integrations / Agents now have their own top-level homes.
const SETTINGS_LABELS: Record<string, string> = {
  general: 'General',
  members: 'Members & Roles',
  'api-keys': 'API Keys',
  notifications: 'Notifications',
  cli: 'CLI',
};

/**
 * Persistent shell for the Settings section: the `Settings / X` breadcrumb stays
 * mounted while navigating between sub-pages, so only the panel content swaps.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>();
  const pathname = usePathname();

  const settingsRoot = `/app/projects/${projectId}/settings`;
  const rest = pathname.startsWith(settingsRoot)
    ? pathname.slice(settingsRoot.length).replace(/^\//, '')
    : '';
  const segments = rest.split('/').filter(Boolean);
  const section = segments[0] ?? '';

  // Only the leaf settings panels get the shell; deeper/unknown routes pass through.
  const isLeafPanel = section in SETTINGS_LABELS && segments.length <= 1;
  if (!isLeafPanel) return <>{children}</>;

  return (
    <PageContainer>
      <Breadcrumb
        items={[
          { label: 'Settings', href: `${settingsRoot}/general` },
          { label: SETTINGS_LABELS[section] },
        ]}
        className="mb-2"
      />
      {children}
    </PageContainer>
  );
}
