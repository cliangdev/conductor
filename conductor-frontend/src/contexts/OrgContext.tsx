'use client'

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { Org, Team } from '@/types'

export interface OrgContextValue {
  orgs: Org[]
  activeOrg: Org | null
  teams: Team[]
  loading: boolean
  needsOnboarding: boolean
  refetch: () => Promise<void>
  setActiveOrg: (org: Org) => void
}

const OrgContext = createContext<OrgContextValue | null>(null)

export function useOrg(): OrgContextValue {
  const ctx = useContext(OrgContext)
  if (!ctx) throw new Error('useOrg must be used within OrgProvider')
  return ctx
}

function getStoredOrgId(): string | null {
  if (typeof window === 'undefined') return null
  return localStorage.getItem('active_org_id')
}

export function OrgProvider({ children }: { children: ReactNode }) {
  const { accessToken } = useAuth()
  const [orgs, setOrgs] = useState<Org[]>([])
  const [activeOrgId, setActiveOrgId] = useState<string | null>(getStoredOrgId)
  const [teams, setTeams] = useState<Team[]>([])
  const [loading, setLoading] = useState(true)
  const [needsOnboarding, setNeedsOnboarding] = useState(false)

  const activeOrg = orgs.find((o) => o.id === activeOrgId) ?? (orgs.length > 0 ? orgs[0] : null)

  const fetchTeams = useCallback(async (orgId: string, token: string) => {
    try {
      const data = await apiGet<Team[]>(`/api/v1/orgs/${orgId}/teams`, token)
      setTeams(data)
    } catch {
      setTeams([])
    }
  }, [])

  const fetchOrgs = useCallback(async () => {
    if (!accessToken) {
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      const data = await apiGet<Org[]>('/api/v1/users/me/orgs', accessToken)
      setOrgs(data)
      setNeedsOnboarding(data.length === 0)
      if (data.length > 0) {
        const orgId = activeOrgId ?? data[0].id
        await fetchTeams(orgId, accessToken)
      }
    } catch (err) {
      console.error('[OrgContext] Failed to load orgs:', err)
    } finally {
      setLoading(false)
    }
  }, [accessToken, activeOrgId, fetchTeams])

  useEffect(() => {
    fetchOrgs()
  }, [fetchOrgs])

  // Refresh teams when active org changes
  useEffect(() => {
    if (activeOrg && accessToken) {
      fetchTeams(activeOrg.id, accessToken)
    }
  }, [activeOrg?.id, accessToken, fetchTeams])

  function setActiveOrg(org: Org) {
    setActiveOrgId(org.id)
    localStorage.setItem('active_org_id', org.id)
  }

  return (
    <OrgContext.Provider value={{ orgs, activeOrg, teams, loading, needsOnboarding, refetch: fetchOrgs, setActiveOrg }}>
      {children}
    </OrgContext.Provider>
  )
}
