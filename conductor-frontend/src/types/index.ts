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
  /** Held roles. Absent on an older server — read through `memberRoles()`. */
  roles?: MemberRole[]
  /** @deprecated Primary role only; read through `memberRoles()`. */
  role?: MemberRole
  joinedAt: string
}

export interface Invite {
  id: string
  email: string
  roles?: MemberRole[]
  /** @deprecated Read through `memberRoles()`. */
  role?: MemberRole
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

