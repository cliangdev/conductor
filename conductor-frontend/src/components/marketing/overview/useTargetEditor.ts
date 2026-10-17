'use client'

// Optimistic target editing shared by the overview and the channel detail page: the new target shows
// on the card at once, and reverts if the server refuses it (the error is rethrown for the dialog).

import { useCallback, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { deleteMarketingTarget, setMarketingTarget } from './types'
import type { ChannelKey, MarketingTarget, OverviewMetric, TargetDirection } from './types'

export interface EditingTarget {
  channel: ChannelKey
  metric: OverviewMetric
}

export function useTargetEditor(
  projectId: string,
  patchTarget: (channel: ChannelKey, metric: string, target: MarketingTarget | null) => void,
) {
  const { accessToken } = useAuth()
  const [editing, setEditing] = useState<EditingTarget | null>(null)

  const save = useCallback(
    async (body: { target: number; direction: TargetDirection }) => {
      if (!editing || !accessToken || editing.metric.target?.readOnly) return
      const { channel, metric } = editing
      patchTarget(channel, metric.metric, { value: body.target, direction: body.direction })
      try {
        const stored = await setMarketingTarget(projectId, accessToken, channel, metric.metric, body)
        if (stored) patchTarget(channel, metric.metric, stored)
      } catch (err) {
        patchTarget(channel, metric.metric, metric.target ?? null)
        throw err
      }
    },
    [editing, accessToken, projectId, patchTarget],
  )

  const remove = useCallback(async () => {
    if (!editing || !accessToken || editing.metric.target?.readOnly) return
    const { channel, metric } = editing
    patchTarget(channel, metric.metric, null)
    try {
      await deleteMarketingTarget(projectId, accessToken, channel, metric.metric)
    } catch (err) {
      patchTarget(channel, metric.metric, metric.target ?? null)
      throw err
    }
  }, [editing, accessToken, projectId, patchTarget])

  return { editing, setEditing, save, remove }
}
