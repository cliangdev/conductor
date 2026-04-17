export interface User {
  id: string
  name: string
  email: string
  avatarUrl: string | null
  displayName: string | null
}

export interface Project {
  id: string
  name: string
  description: string | null
  createdAt: string
  updatedAt: string
  visibility?: 'PRIVATE' | 'ORG' | 'TEAM' | 'PUBLIC'
  teamId?: string | null
  orgId?: string | null
}

export interface AuthResponse {
  accessToken: string
  user: User
}

export type MemberRole = 'ADMIN' | 'CREATOR' | 'REVIEWER'

export interface Member {
  userId: string
  name: string
  email: string
  avatarUrl: string | null
  role: MemberRole
  joinedAt: string
}

export interface Invite {
  id: string
  email: string
  role: MemberRole
  expiresAt: string
  token?: string
}

export interface UserApiKey {
  id: string
  maskedKey: string
  label: string
  createdAt: string
}

export interface CreateApiKeyResponse {
  id: string
  key: string
  maskedKey: string
  label: string
  createdAt: string
}

export interface Org {
  id: string
  name: string
  slug: string
  createdAt: string
}

export type OrgMemberRole = 'ADMIN' | 'MEMBER'

export interface OrgMember {
  userId: string
  name: string
  email: string
  role: OrgMemberRole
  joinedAt: string
}

export interface Team {
  id: string
  orgId: string
  name: string
  createdAt: string
}

export type TeamMemberRole = 'LEAD' | 'MEMBER'

export interface TeamMember {
  userId: string
  name: string
  email: string
  role: TeamMemberRole
  joinedAt: string
}
