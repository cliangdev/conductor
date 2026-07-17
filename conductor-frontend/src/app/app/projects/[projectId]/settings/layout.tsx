'use client';

import { useParams, usePathname } from 'next/navigation';
import { PageContainer } from '@/components/layout/PageContainer';
import { SETTINGS_SECTION_KEYS } from '@/lib/navigation';

/**
 * Persistent shell for the Settings section. Each leaf page owns its own `Settings › X` breadcrumb
 * via its `PageHeader` — this layout only supplies the shared `PageContainer` gutters so there is
 * no duplicate breadcrumb-plus-heading stack.
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
  const isLeafPanel = SETTINGS_SECTION_KEYS.includes(section) && segments.length <= 1;
  if (!isLeafPanel) return <>{children}</>;

  return <PageContainer>{children}</PageContainer>;
}
