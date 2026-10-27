import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
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
    expect(previewFontFamily('-apple-system, BlinkMacSystemFont, sans-serif')).toBe('-apple-system,BlinkMacSystemFont,sans-serif,-apple-system,"Helvetica Neue",sans-serif')
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

  const frame = () => screen.getByTestId('slideshow-style-preview') as HTMLIFrameElement
  const srcDoc = () => frame().getAttribute('srcdoc') ?? ''

  it('renders the preview in a sandboxed iframe without scripts', () => {
    render(<Harness initial={{}} />)
    expect(frame().tagName).toBe('IFRAME')
    // same-origin only so the page can read the frame's height; scripts stay off
    expect(frame().getAttribute('sandbox')).toBe('allow-same-origin')
    expect(frame().getAttribute('sandbox')).not.toContain('allow-scripts')
  })

  it('sizes the preview from its content instead of a fixed height', () => {
    render(<Harness initial={{}} />)
    expect(frame().className).not.toMatch(/\bh-24\b/)
    expect(frame().style.height).not.toBe('')
  })

  it('rebuilds the preview document once per debounce window while a colour is dragged', async () => {
    vi.useFakeTimers()
    try {
      render(<Harness initial={{}} />)
      const before = srcDoc()
      const picker = screen.getByLabelText('Accent colour')
      for (const c of ['#111111', '#222222', '#333333', '#444444', '#555555']) {
        fireEvent.change(picker, { target: { value: c } })
        await act(async () => { vi.advanceTimersByTime(30) })
        expect(srcDoc()).toBe(before)
      }
      await act(async () => { vi.advanceTimersByTime(200) })
      expect(srcDoc()).not.toBe(before)
      expect(srcDoc()).toContain('#555555')
      expect(srcDoc()).not.toContain('#333333')
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not show the https message while the URL is still being typed, only once it has settled or on blur', async () => {
    vi.useFakeTimers()
    try {
      render(<Harness initial={{}} />)
      const input = screen.getByLabelText('Slide font URL')
      fireEvent.change(input, { target: { value: 'h' } })
      fireEvent.change(input, { target: { value: 'ht' } })
      expect(screen.queryByText(/https:\/\/ link/)).not.toBeInTheDocument()
      await act(async () => { vi.advanceTimersByTime(600) })
      expect(screen.getByText(/https:\/\/ link/)).toBeInTheDocument()
      fireEvent.change(input, { target: { value: 'htt' } })
      expect(screen.queryByText(/https:\/\/ link/)).not.toBeInTheDocument()
      await act(async () => { fireEvent.blur(input) })
      expect(screen.getByText(/https:\/\/ link/)).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('puts the chosen font in the iframe document, not on the preview element', () => {
    render(<Harness initial={{ fontFamily: 'Inter, sans-serif' }} />)
    expect(srcDoc()).toContain('font:14px/1.3 "Inter",sans-serif,-apple-system,"Helvetica Neue",sans-serif')
  })

  it('previews outlined text in strokeInk and dark text in darkInk', () => {
    render(<Harness initial={{ darkInk: '#112233', strokeInk: '#ffee00' }} />)
    expect(srcDoc()).toContain('.stroke{font-weight:800;color:#ffee00')
    expect(srcDoc()).toContain('color:#112233')
    expect(screen.getByLabelText('Outlined text colour')).toHaveValue('#ffee00')
  })

  it('adds no link to the iframe while typing within the debounce window', async () => {
    render(<Harness initial={{}} />)
    await userEvent.type(screen.getByLabelText('Slide font URL'), 'https://fonts.example.com/a.css')
    expect(srcDoc()).not.toContain('<link')
  })

  it('links the font inside the iframe after the debounce, and never in the app document head', async () => {
    const url = 'https://fonts.example.com/c.css'
    render(<Harness initial={{}} />)
    await userEvent.type(screen.getByLabelText('Slide font URL'), url)
    await waitFor(() => expect(srcDoc()).toContain(`<link rel="stylesheet" href="${url}">`), { timeout: 2000 })
    expect(links()).toHaveLength(0)
  })

  it('links the font on blur without waiting, still only inside the iframe', async () => {
    const url = 'https://fonts.example.com/b.css'
    const { unmount } = render(<Harness initial={{ fontUrl: url }} />)
    await act(async () => {
      fireEvent.blur(screen.getByLabelText('Slide font URL'))
    })
    expect(srcDoc()).toContain(`href="${url}"`)
    unmount()
    expect(links()).toHaveLength(0)
  })

  it('rejects an http:// font URL: a message, and no link in the preview', async () => {
    render(<Harness initial={{ fontUrl: 'http://fonts.example.com/a.css' }} />)
    await act(async () => {
      fireEvent.blur(screen.getByLabelText('Slide font URL'))
    })
    expect(screen.getByText(/https:\/\/ link/)).toBeInTheDocument()
    expect(srcDoc()).not.toContain('<link')
  })

  it('does not put a font family with forbidden characters into the iframe CSS', () => {
    render(<Harness initial={{ fontFamily: 'A</style><script>x</script>' }} />)
    expect(srcDoc()).not.toContain('<script>')
    expect(srcDoc()).not.toContain('A</style>')
  })
})
