import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { fireEvent } from '@testing-library/react'
import { useState } from 'react'

import {
  SlideshowStyleSection,
  previewFontFamily,
} from './SlideshowStyleSection'
import type { SlideshowStyle } from './types'

function Harness({ initial }: { initial: SlideshowStyle }) {
  const [v, setV] = useState<SlideshowStyle | null>(initial)
  return <SlideshowStyleSection value={v} onChange={setV} />
}

const links = () => Array.from(document.head.querySelectorAll('link[rel="stylesheet"]'))

afterEach(() => {
  links().forEach((l) => l.remove())
})

describe('previewFontFamily', () => {
  it('quotes names and appends the composer fallbacks', () => {
    expect(previewFontFamily('8bit Wonder')).toBe('"8bit Wonder",-apple-system,"Helvetica Neue",sans-serif')
    expect(previewFontFamily('Inter, sans-serif')).toBe('"Inter",sans-serif,-apple-system,"Helvetica Neue",sans-serif')
    expect(previewFontFamily(' , ')).toBeUndefined()
    expect(previewFontFamily(undefined)).toBeUndefined()
  })
})

describe('SlideshowStyleSection', () => {
  it('shows the native value on the picker for an unset colour', () => {
    render(<Harness initial={{}} />)
    expect(screen.getByLabelText('Accent colour')).toHaveValue('#ff2d55')
    expect(screen.getByLabelText('Box background colour')).toHaveValue('#ffffff')
    expect(screen.getByLabelText('Dark background colour')).toHaveValue('#111111')
  })

  it('uses the composer font stack in the preview', () => {
    render(<Harness initial={{ fontFamily: 'Inter, sans-serif' }} />)
    expect(screen.getByTestId('slideshow-style-preview').style.fontFamily).toContain('Inter')
  })

  it('adds no link while typing within the debounce window', async () => {
    render(<Harness initial={{}} />)
    await userEvent.type(screen.getByLabelText('Slide font URL'), 'https://fonts.example.com/a.css')
    expect(links().filter((l) => l.getAttribute('href')?.includes('fonts.example.com'))).toHaveLength(0)
  })

  it('adds the link on blur, reuses an existing one, and never removes it on unmount', async () => {
    const url = 'https://fonts.example.com/a.css'
    const existing = document.createElement('link')
    existing.rel = 'stylesheet'
    existing.href = url
    document.head.appendChild(existing)
    const { unmount } = render(<Harness initial={{ fontUrl: url }} />)
    const input = screen.getByLabelText('Slide font URL')
    await act(async () => {
      fireEvent.blur(input)
    })
    expect(links().filter((l) => l.getAttribute('href') === url)).toHaveLength(1)
    unmount()
    expect(document.head.contains(existing)).toBe(true)
  })

  it('adds a missing link on blur and keeps it after unmount', async () => {
    const url = 'https://fonts.example.com/b.css'
    const { unmount } = render(<Harness initial={{ fontUrl: url }} />)
    await act(async () => {
      fireEvent.blur(screen.getByLabelText('Slide font URL'))
    })
    expect(links().filter((l) => l.getAttribute('href') === url)).toHaveLength(1)
    unmount()
    expect(links().filter((l) => l.getAttribute('href') === url)).toHaveLength(1)
  })
})
