import { afterEach, describe, expect, it, vi } from 'vitest'
import { triggerBrowserDownload } from './browserDownload'

describe('triggerBrowserDownload', () => {
  afterEach(() => vi.useRealTimers())
  it('uses a safe fallback and revokes its object URL', () => {
    vi.useFakeTimers()
    const create = vi.fn(() => 'blob:temporary')
    const revoke = vi.fn()
    URL.createObjectURL = create
    URL.revokeObjectURL = revoke
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    triggerBrowserDownload({ blob: new Blob(['x']), filename: '../bad:name.pdf' }, 'fallback.pdf')
    expect(create).toHaveBeenCalledOnce()
    vi.advanceTimersByTime(10_000)
    expect(revoke).toHaveBeenCalledWith('blob:temporary')
  })
})
