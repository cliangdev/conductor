'use client';

import Link from 'next/link';
import { useParams, usePathname } from 'next/navigation';
import { PageContainer } from '@/components/layout/PageContainer';
import { usePermissions } from '@/contexts/PermissionsContext';
import { SETTINGS_NAV, visibleNavEntries } from '@/lib/navigation';
import { cn } from '@/lib/utils';

/**
 * Persistent shell for the Settings section — the settings area's own sub-nav rail, replacing the
 * sidebar's old always-expanded Settings subtree (issue #290). Same w-56 rail idiom as the
 * Knowledge area (knowledge/layout.tsx). Each leaf page still owns its own `Settings › X`
 * breadcrumb via PageHeader, so this layout only supplies the rail plus the shared PageContainer
 * gutters for the content pane — no duplicate breadcrumb-plus-heading stack.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>();
  const pathname = usePathname();
  const { can } = usePermissions();

  const sections = visibleNavEntries(SETTINGS_NAV, can);

  return (
    <div className="flex h-full">
      {/* Left rail: settings sections, gated the same way as the Sidebar door and command palette */}
      <div className="w-56 shrink-0 border-r border-border bg-sidebar-bg h-full overflow-y-auto py-2 px-1 space-y-0.5">
        {sections.map(({ key, label, icon: Icon, path }) => {
          const href = path(projectId);
          const isActive = pathname === href;
          return (
            <Link
              key={key}
              href={href}
              aria-current={isActive ? 'page' : undefined}
              className={cn(
                'flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-sm transition-colors',
                isActive
                  ? 'bg-sidebar-active text-sidebar-active-text font-medium'
                  : 'text-foreground hover:bg-sidebar-hover'
              )}
            >
              <Icon className="h-3.5 w-3.5 shrink-0 opacity-70" />
              <span className="truncate">{label}</span>
            </Link>
          );
        })}
      </div>

      {/* Right panel: the active section */}
      <div className="flex-1 overflow-y-auto">
        <PageContainer>{children}</PageContainer>
      </div>
    </div>
  );
}
