import { beforeEach, describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { act, render, screen } from '@testing-library/react'
import { usePersistedStateAfterMount } from './persisted'

const isWindow = (v: string): v is '7d' | '28d' => v === '7d' || v === '28d'

function Probe() {
  const [value, , ready] = usePersistedStateAfterMount('test.window', isWindow, '28d')
  return <p>{`${value}:${ready ? 'ready' : 'pending'}`}</p>
}

beforeEach(() => localStorage.clear())

describe('usePersistedStateAfterMount', () => {
  it('renders the fallback on the server even when a value is saved, so hydration matches', () => {
    localStorage.setItem('test.window', '7d')
    expect(renderToString(<Probe />)).toContain('28d:pending')
  })

  it('applies the saved value after mount and reports ready', async () => {
    localStorage.setItem('test.window', '7d')
    await act(async () => {
      render(<Probe />)
    })
    expect(screen.getByText('7d:ready')).toBeInTheDocument()
  })

  it('keeps the fallback when nothing valid is saved', async () => {
    localStorage.setItem('test.window', 'bogus')
    await act(async () => {
      render(<Probe />)
    })
    expect(screen.getByText('28d:ready')).toBeInTheDocument()
  })
})
