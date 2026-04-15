'use client'

import { useCallback, useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'

export interface NotificationChannelResponse {
  eventType: string
  provider: string
  webhookUrl: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface NotificationChannelRequest {
  provider: string
  webhookUrl: string
  enabled: boolean
}

export interface NotificationEventConfig {
  eventType: string
  label: string
  enabled: boolean
}

export interface NotificationGroupResponse {
  channelGroup: string
  label: string
  provider: string
  webhookUrl: string
  enabled: boolean
  events: NotificationEventConfig[]
  createdAt: string
  updatedAt: string
}

export interface NotificationGroupRequest {
  provider: string
  webhookUrl: string
  enabled: boolean
  enabledEventTypes: string[]
}

export interface NotificationTestResponse {
  success: boolean
  message: string
}

export const EVENT_TYPE_DESCRIPTIONS: Record<string, string> = {
  ISSUE_SUBMITTED: 'PRD submitted for review',
  ISSUE_APPROVED: 'PRD approved',
  ISSUE_IN_PROGRESS: 'Issue moved to In Progress',
  ISSUE_IN_CODE_REVIEW: 'Issue moved to Code Review',
  ISSUE_COMPLETED: 'Issue marked as Done',
  ISSUE_STATUS_CHANGED: 'Any issue status change',
  REVIEWER_ASSIGNED: 'Reviewer assigned to a PRD',
  REVIEW_SUBMITTED: 'Review submitted (approved/changes requested)',
  COMMENT_ADDED: 'Comment added to a PRD',
  COMMENT_REPLY: 'Reply added to a comment',
  MEMBER_JOINED: 'New member joined the project',
  MEMBER_ROLE_CHANGED: 'Member role changed',
}

export const EVENT_TYPE_SUBTITLES: Record<string, string> = {
  ISSUE_SUBMITTED: 'When an issue is moved from Draft to In Review',
  ISSUE_APPROVED: 'When an issue is approved and moved to Ready for Development',
  ISSUE_IN_PROGRESS: 'When development begins on an issue',
  ISSUE_IN_CODE_REVIEW: 'When an issue is submitted for code review',
  ISSUE_COMPLETED: 'When an issue is marked as Done',
  ISSUE_STATUS_CHANGED: 'Fires on every status transition (includes from/to status)',
  REVIEWER_ASSIGNED: 'When a reviewer is added to an issue',
  REVIEW_SUBMITTED: 'When a reviewer submits an approval or change request',
  COMMENT_ADDED: 'When a new comment is posted on an issue',
  COMMENT_REPLY: 'When someone replies to an existing comment',
  MEMBER_JOINED: 'When a new member accepts a project invitation',
  MEMBER_ROLE_CHANGED: "When a member's role is updated",
}

export const ALL_EVENT_TYPES = Object.keys(EVENT_TYPE_DESCRIPTIONS)

export const CHANNEL_GROUPS: { value: string; label: string; eventTypes: string[] }[] = [
  {
    value: 'ISSUES',
    label: 'Issues',
    eventTypes: [
      'ISSUE_SUBMITTED',
      'ISSUE_APPROVED',
      'ISSUE_IN_PROGRESS',
      'ISSUE_IN_CODE_REVIEW',
      'ISSUE_COMPLETED',
      'ISSUE_STATUS_CHANGED',
      'REVIEWER_ASSIGNED',
      'REVIEW_SUBMITTED',
      'COMMENT_ADDED',
      'COMMENT_REPLY',
    ],
  },
  {
    value: 'MEMBERS',
    label: 'Members',
    eventTypes: ['MEMBER_JOINED', 'MEMBER_ROLE_CHANGED'],
  },
]

interface UseNotificationGroupsResult {
  groups: NotificationGroupResponse[]
  loading: boolean
  error: string | null
  refetch: () => void
}

export function useNotificationGroups(
  projectId: string,
  accessToken: string
): UseNotificationGroupsResult {
  const [groups, setGroups] = useState<NotificationGroupResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchGroups = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await apiGet<NotificationGroupResponse[]>(
        `/api/v1/projects/${projectId}/notifications/groups`,
        accessToken
      )
      setGroups(data)
    } catch {
      setError('Failed to load notification settings.')
    } finally {
      setLoading(false)
    }
  }, [projectId, accessToken])

  useEffect(() => {
    if (projectId && accessToken) {
      fetchGroups()
    }
  }, [fetchGroups, projectId, accessToken])

  return { groups, loading, error, refetch: fetchGroups }
}

interface UseNotificationsResult {
  channels: NotificationChannelResponse[]
  loading: boolean
  error: string | null
  refetch: () => void
}

export function useNotifications(projectId: string, accessToken: string): UseNotificationsResult {
  const [channels, setChannels] = useState<NotificationChannelResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchChannels = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await apiGet<NotificationChannelResponse[]>(
        `/api/v1/projects/${projectId}/notifications/channels`,
        accessToken
      )
      setChannels(data)
    } catch {
      setError('Failed to load notification channels.')
    } finally {
      setLoading(false)
    }
  }, [projectId, accessToken])

  useEffect(() => {
    if (projectId && accessToken) {
      fetchChannels()
    }
  }, [fetchChannels, projectId, accessToken])

  return { channels, loading, error, refetch: fetchChannels }
}
