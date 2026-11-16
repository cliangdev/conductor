import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useCommentDeepLink } from './useCommentDeepLink'

function setUrl(search: string) {
  window.history.replaceState(null, '', `/app/projects/p1/engineering/issues/ENG-1${search}`)
}

afterEach(() => setUrl(''))

describe('useCommentDeepLink', () => {
  it('has nothing to focus without ?comment=', () => {
    setUrl('')
    const { result } = renderHook(() => useCommentDeepLink())
    expect(result.current.focusCommentId).toBeNull()
  })

  it('on a project doc: focuses ?comment= and clears it without touching other params', () => {
    setUrl('?comment=c9&tab=x')
    const { result } = renderHook(() => useCommentDeepLink())
    expect(result.current.focusCommentId).toBe('c9')
    act(() => result.current.clear())
    expect(result.current.focusCommentId).toBeNull()
    expect(window.location.search).toBe('?tab=x')
  })

  function docsProps(over: Partial<Parameters<typeof useCommentDeepLink>[0] & object> = {}) {
    return {
      docs: [{ id: 'd1' }, { id: 'd2' }],
      docsLoading: false,
      comments: [{ id: 'c1', documentId: 'd2' }],
      commentsLoading: false,
      selectedDocId: 'd1',
      activeTab: 'd1',
      selectDocument: vi.fn(),
      ...over,
    }
  }

  it('selects the document named by ?doc= and focuses once it is the one on screen', () => {
    setUrl('?comment=c1&doc=d2')
    const props = docsProps()
    const { result, rerender } = renderHook((p) => useCommentDeepLink(p), { initialProps: props })
    expect(props.selectDocument).toHaveBeenCalledWith({ id: 'd2' })
    expect(result.current.focusCommentId).toBeNull()
    rerender({ ...props, selectedDocId: 'd2', activeTab: 'd2' })
    expect(result.current.focusCommentId).toBe('c1')
    act(() => result.current.clear())
    expect(window.location.search).toBe('')
  })

  it('finds the document from the comment when there is no ?doc=', () => {
    setUrl('?comment=c1')
    const props = docsProps()
    renderHook((p) => useCommentDeepLink(p), { initialProps: props })
    expect(props.selectDocument).toHaveBeenCalledWith({ id: 'd2' })
  })

  it('selects the tab only once', () => {
    setUrl('?comment=c1&doc=d2')
    const props = docsProps()
    const { rerender } = renderHook((p) => useCommentDeepLink(p), { initialProps: props })
    rerender({ ...props, docs: [{ id: 'd1' }, { id: 'd2' }] })
    expect(props.selectDocument).toHaveBeenCalledTimes(1)
  })

  it('waits for the documents to load before deciding', () => {
    setUrl('?comment=c1&doc=d2')
    const props = docsProps({ docs: [], docsLoading: true })
    const { result, rerender } = renderHook((p) => useCommentDeepLink(p), { initialProps: props })
    expect(props.selectDocument).not.toHaveBeenCalled()
    expect(window.location.search).toContain('comment=c1')
    rerender({ ...props, docs: [{ id: 'd2' }], docsLoading: false })
    expect(props.selectDocument).toHaveBeenCalledWith({ id: 'd2' })
    expect(result.current.focusCommentId).toBeNull()
  })

  it('drops a link to a document that is not on the page', () => {
    setUrl('?comment=c1&doc=gone')
    const props = docsProps()
    const { result } = renderHook((p) => useCommentDeepLink(p), { initialProps: props })
    expect(result.current.focusCommentId).toBeNull()
    expect(window.location.search).toBe('')
  })

  it('drops a link to a comment that is on no document (an item-level comment)', () => {
    setUrl('?comment=item-level')
    const props = docsProps()
    renderHook((p) => useCommentDeepLink(p), { initialProps: props })
    expect(props.selectDocument).not.toHaveBeenCalled()
    expect(window.location.search).toBe('')
  })
})
