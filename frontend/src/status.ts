export interface ServiceStatus {
  service: 'xk-logistics'
  status: 'UP'
  timestamp: string
}

export async function fetchStatus(signal: AbortSignal): Promise<ServiceStatus> {
  let response: Response
  try {
    response = await fetch('/api/v1/status', {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      signal,
    })
  } catch {
    throw new Error(signal.aborted ? '連線逾時或中斷，請確認服務已啟動後重試。' : '服務暫時無法連線，請稍後重試。')
  }
  if (!response.ok) throw new Error('服務暫時無法連線，請稍後重試。')

  let payload: unknown
  try { payload = await response.json() } catch { throw new Error('服務回應格式不正確，請稍後重試。') }
  if (!payload || typeof payload !== 'object') throw new Error('服務回應格式不正確，請稍後重試。')
  const envelope = payload as Record<string, unknown>
  const data = envelope.data
  if (
    envelope.code !== '0000' ||
    typeof envelope.message !== 'string' ||
    typeof envelope.timestamp !== 'string' ||
    !envelope.timestamp.endsWith('+08:00') ||
    Number.isNaN(Date.parse(envelope.timestamp)) ||
    !data || typeof data !== 'object' ||
    !('service' in data) || data.service !== 'xk-logistics' ||
    !('status' in data) || data.status !== 'UP'
  ) throw new Error('服務回應格式不正確，請稍後重試。')

  return { service: 'xk-logistics', status: 'UP', timestamp: envelope.timestamp }
}
