import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-abc-123' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token', user: null, loading: false }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

import * as api from '@/lib/api'
import SetupPage from './page'

describe('SetupPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.apiGet).mockResolvedValue([])
  })

  it('renders the setup page heading', async () => {
    render(<SetupPage />)
    expect(screen.getByRole('heading', { name: /setup/i, level: 1 })).toBeInTheDocument()
  })

  it('pre-fills conductor init command with the correct projectId', async () => {
    render(<SetupPage />)
    expect(screen.getByText('conductor init --project-id proj-abc-123')).toBeInTheDocument()
  })

  it('renders all five setup steps', async () => {
    render(<SetupPage />)
    expect(screen.getByText('Install')).toBeInTheDocument()
    expect(screen.getByText('Login')).toBeInTheDocument()
    expect(screen.getByText('Initialize project')).toBeInTheDocument()
    expect(screen.getByText('API Key')).toBeInTheDocument()
    expect(screen.getByText('Verify')).toBeInTheDocument()
    expect(screen.queryByText('MCP Configuration')).not.toBeInTheDocument()
  })

  describe('ApiKeySection — no keys', () => {
    it('shows Generate API key button when no keys exist', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([])
      render(<SetupPage />)
      expect(await screen.findByRole('button', { name: /generate api key/i })).toBeInTheDocument()
    })

    it('shows "No API keys yet." text when no keys exist', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([])
      render(<SetupPage />)
      expect(await screen.findByText(/no api keys yet/i)).toBeInTheDocument()
    })
  })

  describe('ApiKeySection — generate key', () => {
    it('shows full key with warning after generating', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([])
      vi.mocked(api.apiPost).mockResolvedValue({
        id: 'key-1',
        key: 'sk-full-secret-key-abc123',
        maskedKey: 'sk-****abc123',
        label: 'My Key',
        createdAt: '2024-01-01T00:00:00Z',
      })

      render(<SetupPage />)
      const generateBtn = await screen.findByRole('button', { name: /generate api key/i })
      fireEvent.click(generateBtn)

      await waitFor(() => {
        expect(screen.getByText("Copy this key — it won't be shown again")).toBeInTheDocument()
      })
      expect(screen.getByText('sk-full-secret-key-abc123')).toBeInTheDocument()
    })

    it('calls apiPost to create an API key', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([])
      vi.mocked(api.apiPost).mockResolvedValue({
        id: 'key-1',
        key: 'sk-full',
        maskedKey: 'sk-****',
        label: '',
        createdAt: '2024-01-01T00:00:00Z',
      })

      render(<SetupPage />)
      const generateBtn = await screen.findByRole('button', { name: /generate api key/i })
      fireEvent.click(generateBtn)

      await waitFor(() => {
        expect(api.apiPost).toHaveBeenCalledWith('/api/v1/api-keys', {}, 'test-token')
      })
    })
  })

  describe('ApiKeySection — existing keys', () => {
    it('shows masked key when keys are loaded', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([
        { id: 'key-1', maskedKey: 'sk-****xyz', label: 'CLI key', createdAt: '2024-01-01T00:00:00Z' },
      ])

      render(<SetupPage />)
      expect(await screen.findByText('sk-****xyz')).toBeInTheDocument()
    })

    it('shows Revoke button for each key', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([
        { id: 'key-1', maskedKey: 'sk-****xyz', label: '', createdAt: '2024-01-01T00:00:00Z' },
      ])

      render(<SetupPage />)
      expect(await screen.findByRole('button', { name: /revoke/i })).toBeInTheDocument()
    })

    it('calls apiDelete and removes key when Revoke is clicked', async () => {
      vi.mocked(api.apiGet).mockResolvedValue([
        { id: 'key-1', maskedKey: 'sk-****xyz', label: '', createdAt: '2024-01-01T00:00:00Z' },
      ])
      vi.mocked(api.apiDelete).mockResolvedValue(undefined)

      render(<SetupPage />)
      const revokeBtn = await screen.findByRole('button', { name: /revoke/i })
      fireEvent.click(revokeBtn)

      await waitFor(() => {
        expect(api.apiDelete).toHaveBeenCalledWith('/api/v1/api-keys/key-1', 'test-token')
      })

      await waitFor(() => {
        expect(screen.queryByText('sk-****xyz')).not.toBeInTheDocument()
      })
    })
  })
})
