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
