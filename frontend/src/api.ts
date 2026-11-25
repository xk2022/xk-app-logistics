export const roles = ['ADMIN', 'DISPATCHER', 'DRIVER'] as const
export type Role = typeof roles[number]
export const roleLabels: Record<Role, string> = { ADMIN: '管理員', DISPATCHER: '調度員', DRIVER: '司機' }
export interface Account {
  id: string
  username: string
  displayName: string
  role: Role
  enabled: boolean
  mustChangePassword: boolean
  version: number
}
export class ApiError extends Error {
  constructor(public status: number, message: string, public fields: Record<string, string> = {}) {
    super(message)
  }
}
interface Csrf { token: string; headerName: string }
let csrf: Csrf | null = null

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
export function parseAccount(value: unknown): Account {
  if (!record(value) || typeof value.id !== 'string' || typeof value.username !== 'string' ||
    typeof value.displayName !== 'string' || !roles.includes(value.role as Role) ||
    typeof value.enabled !== 'boolean' || typeof value.mustChangePassword !== 'boolean' ||
    typeof value.version !== 'number' || !Number.isInteger(value.version)) {
    throw new ApiError(0, '服務回應格式不正確，請稍後重試。')
  }
  return value as unknown as Account
}

async function request(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<unknown> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) controller.abort()
  const timeout = window.setTimeout(abort, 10000)
  try {
    const response = await fetch(`/api/v1${path}`, {
      method,
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(method !== 'GET' && csrf ? { [csrf.headerName]: csrf.token } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: controller.signal,
    })
    let payload: unknown
    try { payload = await response.json() } catch { throw new ApiError(response.status, '服務回應格式不正確，請稍後重試。') }
    if (!record(payload) || typeof payload.code !== 'string' || typeof payload.message !== 'string' ||
      typeof payload.timestamp !== 'string' || Number.isNaN(Date.parse(payload.timestamp)) || !payload.timestamp.endsWith('+08:00')) {
      throw new ApiError(response.status, '服務回應格式不正確，請稍後重試。')
    }
    if (!response.ok || payload.code !== '0000') {
      const fields = record(payload.data) ? Object.fromEntries(Object.entries(payload.data).filter((entry): entry is [string, string] => typeof entry[1] === 'string')) : {}
      const message = response.status === 401 ? '登入失敗或登入狀態已失效，請重新登入。'
        : response.status === 403 ? '權限不足或安全驗證已失效，請確認後重新操作。'
        : response.status >= 500 ? '服務暫時無法使用，請稍後重試。' : payload.message
      throw new ApiError(response.status, message, fields)
    }
    return payload.data
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, controller.signal.aborted ? '連線逾時或中斷，請稍後重試。' : '無法連線，請確認網路後重試。')
  } finally { window.clearTimeout(timeout); signal?.removeEventListener('abort', abort) }
}

export async function refreshCsrf(): Promise<void> {
  csrf = null
  const data = await request('/auth/csrf')
  if (!record(data) || typeof data.token !== 'string' || !data.token || data.headerName !== 'X-CSRF-TOKEN') {
    throw new ApiError(0, '無法取得安全驗證，請重新操作。')
  }
  csrf = { token: data.token, headerName: data.headerName }
}
async function write(path: string, method: string, body?: unknown): Promise<unknown> {
  if (!csrf) await refreshCsrf()
  try { return await request(path, method, body) } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      // A rejected write is never replayed automatically: the caller decides when to submit again.
      try { await refreshCsrf() } catch { csrf = null }
    }
    throw error
  }
}
export async function getMe(signal?: AbortSignal) { return parseAccount(await request('/auth/me', 'GET', undefined, signal)) }
export async function login(username: string, password: string) {
  await refreshCsrf()
  const account = parseAccount(await write('/auth/login', 'POST', { username, password }))
  try { await refreshCsrf() } catch { csrf = null }
  return account
}
export async function logout() {
  await write('/auth/logout', 'POST')
  csrf = null
  try { await refreshCsrf() } catch { csrf = null }
}
export async function changePassword(currentPassword: string, newPassword: string) {
  await write('/auth/password', 'POST', { currentPassword, newPassword })
  csrf = null
  try { await refreshCsrf() } catch { csrf = null }
}
export async function listAccounts(signal?: AbortSignal) {
  const data = await request('/upms/accounts', 'GET', undefined, signal)
  if (!Array.isArray(data)) throw new ApiError(0, '服務回應格式不正確，請稍後重試。')
  return data.map(parseAccount)
}
export async function listRoles(signal?: AbortSignal): Promise<Role[]> {
  const data = await request('/upms/roles', 'GET', undefined, signal)
  if (!Array.isArray(data) || data.length !== roles.length || !roles.every((role) => data.includes(role))) {
    throw new ApiError(0, '角色回應格式不正確，請稍後重試。')
  }
  return [...roles]
}
export async function createAccount(data: {username: string; displayName: string; role: Role; password: string}) {
  return parseAccount(await write('/upms/accounts', 'POST', data))
}
export async function updateAccount(id: string, data: {displayName: string; role: Role; enabled: boolean; version: number}) {
  return parseAccount(await write(`/upms/accounts/${encodeURIComponent(id)}`, 'PATCH', data))
}
