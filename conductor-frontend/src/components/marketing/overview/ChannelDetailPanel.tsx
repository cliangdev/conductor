"use client";

// One channel in detail: every metric as a chart (provisional days dashed, target as a reference line),
// the breakdown tables, and for Social the top posts. Reads GET …/marketing/channels/{channel}.

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ArrowLeftIcon, SearchXIcon } from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { useAuth } from "@/contexts/AuthContext";
import { usePermissions } from "@/contexts/PermissionsContext";
import { apiErrorMessage } from "@/lib/api";
import { timeAgo } from "@/lib/format";
import { usePersistedStateAfterMount } from "@/lib/persisted";
import { useSidebarWorkNav, workItemDetailPath } from "@/lib/workflows";
import { PageHeader } from "@/components/layout/PageHeader";
import { InsightsPostList } from "@/components/marketing/insights/InsightsPostList";
import type { InsightsPost } from "@/components/marketing/insights/types";
import { ChangeLine } from "./ChangeLine";
import { ChannelStatusPill } from "./ChannelStatusPill";
import { DimensionTable } from "./DimensionTable";
import { MetricChart } from "./MetricChart";
import { MetricFlags } from "./MetricFlags";
import { MismatchNotice } from "./MismatchNotice";
import { StageChart } from "./StageChart";
import { TargetNote } from "./TargetNote";
import { TargetDialog } from "./TargetDialog";
import { dataThroughLabel, formatMetricValue, shortDate } from "./format";
import { useTargetEditor } from "./useTargetEditor";
import {
  DETAIL_WINDOWS,
  getMarketingChannel,
  isChannelKey,
  type ChannelKey,
  type MarketingChannelDetailResponse,
  type MarketingTarget,
  type OverviewWindowValue,
} from "./types";

const WINDOW_TABS: TabItem[] = DETAIL_WINDOWS.map((w) => ({
  value: w,
  label: w,
}));
const WINDOW_STORAGE_KEY = "marketing.overview.detailWindow";
const MARKETING_AREA = "MARKETING";

function isDetailWindow(value: string): value is OverviewWindowValue {
  return (DETAIL_WINDOWS as string[]).includes(value);
}

export function ChannelDetailPanel({
  projectId,
  channel: channelParam,
}: {
  projectId: string;
  channel: string;
}) {
  const { accessToken } = useAuth();
  const { can } = usePermissions();
  const canEditTargets = can("marketing.target.manage");
  const overviewHref = `/app/projects/${projectId}/marketing/overview`;
  const channelKey = channelParam.toUpperCase();
  const known = isChannelKey(channelKey);

  const [range, setRange, rangeReady] = usePersistedStateAfterMount(
    WINDOW_STORAGE_KEY,
    isDetailWindow,
    "28d",
  );
  const [data, setData] = useState<MarketingChannelDetailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { entries } = useSidebarWorkNav(projectId, accessToken);
  const postWorkflow = useMemo(
    () => entries.find((e) => e.area.toUpperCase() === MARKETING_AREA),
    [entries],
  );

  const load = useCallback(() => {
    if (!accessToken || !known || !rangeReady) return;
    setError(null);
    setData(null);
    getMarketingChannel(projectId, accessToken, channelKey, range)
      .then(setData)
      .catch((err) =>
        setError(
          apiErrorMessage(
            err,
            "Could not load this channel — please try again.",
          ),
        ),
      );
  }, [projectId, accessToken, known, channelKey, range, rangeReady]);

  useEffect(() => {
    void load();
  }, [load]);

  const patchTarget = useCallback(
    (channel: ChannelKey, metric: string, target: MarketingTarget | null) => {
      setData((prev) =>
        prev
          ? {
              ...prev,
              channel: {
                ...prev.channel,
                metrics: prev.channel.metrics.map((m) =>
                  m.metric === metric ? { ...m, target } : m,
                ),
              },
            }
          : prev,
      );
    },
    [],
  );
  const { editing, setEditing, save, remove } = useTargetEditor(
    projectId,
    patchTarget,
  );

  function hrefForPost(post: InsightsPost): string | null {
    if (!post.displayId) return null;
    const workflow = post.workflowSlug
      ? entries.find(
          (e) => e.slug.toLowerCase() === post.workflowSlug!.toLowerCase(),
        )
      : undefined;
    const resolved = workflow ?? postWorkflow;
    return resolved
      ? workItemDetailPath(
          projectId,
          resolved.area,
          resolved.noun,
          post.displayId,
        )
      : null;
  }

  const backLink = (
    <Link prefetch={false}
      href={overviewHref}
      className="inline-flex items-center gap-1 rounded-sm text-sm text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <ArrowLeftIcon className="h-3.5 w-3.5" aria-hidden />
      Marketing overview
    </Link>
  );

  const channel = data?.channel;
  const title = channel?.label ?? "Channel";
  const header = (
    <PageHeader
      breadcrumbs={[
        { label: "Marketing" },
        { label: "Overview", href: overviewHref },
        { label: title },
      ]}
      title={title}
    />
  );

  if (!known) {
    return (
      <>
        {header}
        <EmptyState
          icon={SearchXIcon}
          title="Channel not found"
          description="That channel does not exist. Go back to the overview to pick one."
          action={backLink}
        />
      </>
    );
  }

  const detailsThrough = dataThroughLabel(
    null,
    channel?.dataCompleteThrough ?? data?.window.to,
  );

  return (
    <>
      {header}
      <div className="space-y-6">
        {backLink}

        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <Tabs
            ariaLabel="Time window"
            items={WINDOW_TABS}
            value={range}
            onValueChange={(v) => isDetailWindow(v) && setRange(v)}
          />
          {data && detailsThrough && (
            <p className="text-xs text-muted-foreground">
              Data through {detailsThrough}
              {channel?.metrics.some((m) => (m.provisionalDays ?? 0) > 0) &&
                " · later days (dashed) are still updating and are not in the totals"}
            </p>
          )}
        </div>

        {error && (
          <Alert variant="destructive">
            <div className="flex flex-wrap items-center gap-3">
              <span>{error}</span>
              <Button size="sm" variant="outline" onClick={load}>
                Retry
              </Button>
            </div>
          </Alert>
        )}

        {!data && !error && (
          <div
            className="space-y-4"
            aria-busy="true"
            aria-label="Loading channel"
          >
            <Skeleton className="h-10 w-72" />
            <Skeleton className="h-72 w-full" />
            <Skeleton className="h-48 w-full" />
          </div>
        )}

        {data && channel && (
          <>
            <div
              className="flex flex-wrap items-center gap-x-3 gap-y-1"
              aria-label="Channel status"
            >
              <ChannelStatusPill status={channel.status} />
              {channel.lastFetchedAt && (
                <span className="text-xs text-muted-foreground">
                  Last updated {timeAgo(channel.lastFetchedAt)}
                </span>
              )}
              {channel.connections.length > 0 && (
                <ul
                  className="flex flex-wrap gap-x-3 text-xs text-muted-foreground"
                  aria-label="Connections"
                >
                  <li>{channel.connections.length > 1 ? "Sources:" : "Source:"}</li>
                  {channel.connections.map((c) => (
                    <li key={c.id}>
                      <Link prefetch={false}
                        href={`/app/projects/${projectId}/integrations/${c.connectorId}`}
                        className="rounded-sm hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        {c.name}
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <MismatchNotice channel={channel} />

            {channel.channel === "PRODUCT" && (
              <StageChart metrics={channel.metrics} />
            )}

            {channel.metrics.length === 0 ? (
              <EmptyState
                icon={SearchXIcon}
                title={
                  channel.status === "COMING_SOON"
                    ? "Coming soon"
                    : "No data yet"
                }
                description={
                  channel.status === "NOT_CONNECTED"
                    ? "Connect a data source to see this channel."
                    : "This channel has no metrics for this window."
                }
                action={
                  channel.status === "NOT_CONNECTED" &&
                  (channel.connectHint?.connectors.length ?? 0) > 0 ? (
                    <div className="flex flex-wrap justify-center gap-2">
                      {channel.connectHint!.connectors.map((c) => (
                        <Link prefetch={false}
                          key={c.connectorId}
                          href={`/app/projects/${projectId}/integrations/${c.connectorId}`}
                          className={buttonVariants({
                            variant: "outline",
                            size: "sm",
                          })}
                        >
                          Connect {c.name}
                        </Link>
                      ))}
                    </div>
                  ) : undefined
                }
              />
            ) : (
              <div className="grid gap-4 lg:grid-cols-2">
                {channel.metrics.map((m) => (
                  <section
                    key={m.metric}
                    aria-label={m.label}
                    className="rounded-lg border border-border bg-card p-4"
                  >
                    <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <p className="text-xs font-medium text-muted-foreground">
                          {m.label}
                        </p>
                        {m.description && (
                          <p className="text-xs text-muted-foreground">{m.description}</p>
                        )}
                        <p className="text-2xl font-semibold tabular-nums text-foreground">
                          {formatMetricValue(m.value, m.unit)}
                        {m.asOf && (
                          <span className="ml-2 text-xs font-normal text-muted-foreground">as of {shortDate(m.asOf)}</span>
                        )}
                      </p>
                        <ChangeLine metric={m} />
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        {channel.channel === "PRODUCT" && <MetricFlags metric={m} />}
                        <TargetNote
                          metric={m}
                          canEdit={canEditTargets}
                          editable={channel.targetsEditable !== false}
                          ownerLabel={channel.connections[0]?.name}
                          onEdit={() =>
                            setEditing({ channel: channel.channel, metric: m })
                          }
                        />
                      </div>
                    </div>
                    <MetricChart metric={m} />
                  </section>
                ))}
              </div>
            )}

            {data.dimensions.length > 0 && (
              <div className="space-y-6">
                {data.dimensions.map((d) => (
                  <DimensionTable key={d.key} dimension={d} />
                ))}
              </div>
            )}

            {channel.channel === "SOCIAL" &&
              ((data.topPosts?.length ?? 0) > 0 ? (
                <InsightsPostList
                  title="Top posts"
                  posts={data.topPosts ?? []}
                  hrefFor={hrefForPost}
                />
              ) : (
                <section aria-label="Top posts" className="space-y-2">
                  <h3 className="text-sm font-semibold text-foreground">
                    Top posts
                  </h3>
                  <p className="rounded-lg border border-border bg-card px-4 py-6 text-sm text-muted-foreground">
                    No published posts in this window.
                  </p>
                </section>
              ))}
          </>
        )}

        <TargetDialog
          open={!!editing}
          onOpenChange={(open) => !open && setEditing(null)}
          metric={editing?.metric ?? null}
          onSave={save}
          onRemove={remove}
        />
      </div>
    </>
  );
}
