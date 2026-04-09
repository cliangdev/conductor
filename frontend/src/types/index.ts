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
