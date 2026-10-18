import { currentPage, enqueue, isTelemetryEnabled, navigation, nowMs } from '@/lib/telemetry'

/**
 * Record that a page's primary data has rendered: ms since the current navigation started (the
 * document load for the first page) and how many API calls it took. Fires once per navigation -
 * later calls in the same navigation are ignored - and never throws.
 *
 * Call it from an effect once the main content is on screen:
 *   useEffect(() => { if (item) markPageReady('work-item-detail') }, [item])
 */
export function markPageReady(pageName: string) {
  try {
    if (!isTelemetryEnabled() || navigation.readyMarked) return
    navigation.readyMarked = true
    enqueue({
      type: 'page_ready',
      page: pageName,
      path: currentPage(),
      ms: Math.round(nowMs() - navigation.startedAt),
      apiCalls: navigation.apiCalls,
    })
  } catch { /* telemetry must never throw */ }
}
