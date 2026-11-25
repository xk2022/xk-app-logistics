import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

const healthy = {
  code: '0000', message: '成功', data: { service: 'xk-logistics', status: 'UP' },
  timestamp: '2026-11-25T12:00:00+08:00',
}
const jsonResponse = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'content-type': 'application/json' },
})

describe('service status shell', () => {
  it('shows loading then a healthy service without exposing business actions', async () => {
    let resolve!: (response: Response) => void
    const fetch = vi.fn(() => new Promise<Response>((done) => { resolve = done }))
    vi.stubGlobal('fetch', fetch)
    render(<App />)
    expect(screen.getByRole('heading', { name: '系統總覽', level: 1 })).toBeVisible()
    expect(screen.getByRole('button', { name: '檢查中…' })).toBeDisabled()
    await act(async () => { resolve(jsonResponse(healthy)) })
    expect(await screen.findByRole('heading', { name: '系統服務已就緒' })).toBeVisible()
    expect(screen.getByText(/台北時間/)).toBeVisible()
    expect(fetch).toHaveBeenCalledWith('/api/v1/status', expect.objectContaining({ credentials: 'same-origin' }))
    expect(screen.queryByRole('button', { name: /建立訂單|登入/ })).not.toBeInTheDocument()
  })

  it('shows an HTTP failure and retries successfully', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(jsonResponse({ message: 'private diagnostic' }, 503))
      .mockResolvedValueOnce(jsonResponse(healthy))
    vi.stubGlobal('fetch', fetch)
    render(<App />)
    expect(await screen.findByRole('heading', { name: '暫時無法取得服務狀態' })).toBeVisible()
    expect(screen.queryByText('private diagnostic')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重新連線' }))
    expect(await screen.findByRole('heading', { name: '系統服務已就緒' })).toBeVisible()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it.each([
    null,
    { ...healthy, code: 'E0503' },
    { ...healthy, data: { service: 'xk-logistics', status: 'DOWN' } },
    { ...healthy, timestamp: 'not-a-date+08:00' },
    { ...healthy, timestamp: '2026-11-25T04:00:00Z' },
  ])('rejects an invalid success envelope %#', async (payload) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(payload)))
    render(<App />)
    expect(await screen.findByText('服務回應格式不正確，請稍後重試。')).toBeVisible()
    expect(screen.queryByText('系統服務已就緒')).not.toBeInTheDocument()
  })

  it('handles network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    render(<App />)
    expect(await screen.findByRole('button', { name: '重新連線' })).toBeEnabled()
    expect(screen.getByText('服務暫時無法連線，請稍後重試。')).toBeVisible()
    expect(screen.queryByText('Failed to fetch')).not.toBeInTheDocument()
  })

  it('handles a non-JSON response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>proxy error</html>')))
    render(<App />)
    expect(await screen.findByText('服務回應格式不正確，請稍後重試。')).toBeVisible()
  })

  it('times out a stalled request and allows retry', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise<Response>((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    try {
      render(<App />)
      await act(async () => { await vi.advanceTimersByTimeAsync(8000) })
      expect(screen.getByText('連線逾時或中斷，請確認服務已啟動後重試。')).toBeVisible()
      expect(screen.getByRole('button', { name: '重新連線' })).toBeEnabled()
    } finally { vi.useRealTimers() }
  })

  it('aborts a request when the shell is removed', () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => {
      signal = options.signal as AbortSignal
      return new Promise<Response>(() => {})
    }))
    const { unmount } = render(<App />)
    unmount()
    expect(signal?.aborted).toBe(true)
  })
})
