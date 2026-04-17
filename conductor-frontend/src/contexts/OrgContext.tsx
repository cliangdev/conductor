'use client'

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { Org } from '@/types'

export interface OrgContextValue {
  orgs: Org[]
  activeOrg: Org | null
  loading: boolean
  needsOnboarding: boolean
  refetch: () => Promise<void>
}

const OrgContext = createContext<OrgContextValue | null>(null)

export function useOrg(): OrgContextValue {
  const ctx = useContext(OrgContext)
  if (!ctx) throw new Error('useOrg must be used within OrgProvider')
  return ctx
}

export function OrgProvider({ children }: { children: ReactNode }) {
  const { accessToken } = useAuth()
  const [orgs, setOrgs] = useState<Org[]>([])
  const [loading, setLoading] = useState(true)
  const [needsOnboarding, setNeedsOnboarding] = useState(false)

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
    } catch {
      // Leave orgs empty on error
    } finally {
      setLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    fetchOrgs()
  }, [fetchOrgs])

  const activeOrg = orgs.length > 0 ? orgs[0] : null

  return (
    <OrgContext.Provider value={{ orgs, activeOrg, loading, needsOnboarding, refetch: fetchOrgs }}>
      {children}
    </OrgContext.Provider>
  )
}
