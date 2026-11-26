import { ApiError, getCsrfHeaders, refreshCsrf } from './api'
import { parseTomOrder, readTomJson, TomApiError, tomErrorCodes } from './tomApi'
import type { FieldIssue, Order, TomErrorCode, TomWriteResult } from './tomApi'

export interface AcceptanceRequest { expectedVersion: number }
function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function exact(value: unknown, fields: readonly string[]): value is Record<string, unknown> { return record(value) && Object.keys(value).length === fields.length && fields.every((field) => Object.hasOwn(value, field)) }
function full(pattern: RegExp, value: string): boolean { return pattern.exec(value)?.[0] === value }
function safeInteger(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 }
function text(value: unknown, maximum: number): value is string { return typeof value === 'string' && [...value].length <= maximum && /\S/u.test(value) }
function invalidInput(message: string): never { throw new TomApiError(400, 'CLIENT_VALIDATION_ERROR', message) }
function invalidResponse(status: number, aborted: boolean): TomApiError {
  return new TomApiError(status, aborted ? 'REQUEST_ABORTED' : 'INVALID_RESPONSE', aborted ? '受理連線逾時或中斷；請確認原次受理結果。' : '受理服務回應格式不正確；請確認原次受理結果。', { uncertain: true })
}
const statusForCode: Record<TomErrorCode, number> = { VALIDATION_ERROR: 400, INVALID_CUSTOMER: 400, UNAUTHENTICATED: 401, FORBIDDEN: 403, CSRF_INVALID: 403, ORDER_NOT_FOUND: 404, VERSION_CONFLICT: 409, ORDER_NOT_EDITABLE: 409, PROTECTED_RELATION: 409, IDEMPOTENCY_KEY_REUSED: 409, IDEMPOTENCY_IN_PROGRESS: 409, INTERNAL_ERROR: 500 }
function parseError(payload: unknown, status: number): TomApiError {
  if (!exact(payload, ['code', 'message', 'data', 'traceId', 'fieldErrors']) || !tomErrorCodes.includes(payload.code as TomErrorCode) || statusForCode[payload.code as TomErrorCode] !== status || !text(payload.message, 2000) || !text(payload.traceId, 200)
    || !Array.isArray(payload.fieldErrors) || !payload.fieldErrors.every((issue) => exact(issue, ['path', 'fieldId', 'ruleId', 'message']) && Object.values(issue).every((value) => typeof value === 'string'))) throw new Error('Invalid acceptance error')
  let actualVersion: number | undefined
  if (payload.data !== null) {
    if (!record(payload.data) || Object.keys(payload.data).some((field) => field !== 'actualVersion') || Object.hasOwn(payload.data, 'actualVersion') && !safeInteger(payload.data.actualVersion)) throw new Error('Invalid acceptance error data')
    actualVersion = payload.data.actualVersion as number | undefined
  }
  const message = status === 401 ? '登入狀態已失效，請重新登入。' : status >= 500 ? '服務暫時無法使用，請稍後重試。' : payload.message as string
  return new TomApiError(status, payload.code as TomErrorCode, message, { traceId: payload.traceId as string, fieldErrors: payload.fieldErrors as FieldIssue[], actualVersion, uncertain: status >= 500 })
}

/** One explicit attempt only. The caller retains its original key and body after uncertainty. */
export async function acceptOrder(id: string, body: AcceptanceRequest, key: string, signal?: AbortSignal): Promise<TomWriteResult<Order>> {
  if (typeof id !== 'string' || !full(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, id)) invalidInput('訂單識別格式不正確。')
  if (!exact(body, ['expectedVersion']) || ![Object.prototype, null].includes(Object.getPrototypeOf(body)) || Reflect.ownKeys(body).length !== 1) invalidInput('受理請求只能包含已保存版本。')
  const expectedVersion = body.expectedVersion
  if (!safeInteger(expectedVersion)) invalidInput('受理版本須為 0 至 9007199254740991 的整數。')
  if (typeof key !== 'string' || !full(/^[A-Za-z0-9._:-]{1,128}$/, key)) invalidInput('請提供明確有效的受理重送識別。')
  // Snapshot all caller-owned data before awaiting security-token acquisition.
  const orderId = id.toLowerCase(), serialized = JSON.stringify({ expectedVersion }), originalKey = key
  if (signal?.aborted) throw new TomApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  let csrfHeaders: Record<string, string>
  try { csrfHeaders = await getCsrfHeaders(signal) }
  catch (failure) {
    if (signal?.aborted) throw new TomApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
    if (failure instanceof ApiError) throw new TomApiError(failure.status, failure.status === 401 ? 'UNAUTHENTICATED' : 'NETWORK_ERROR', failure.message)
    throw new TomApiError(0, 'NETWORK_ERROR', '無法取得安全驗證，請稍後重試。')
  }
  if (signal?.aborted) throw new TomApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  const controller = new AbortController(), abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timeout = window.setTimeout(abort, 10000)
  try {
    const response = await fetch(`/api/v1/orders/${orderId}/accept`, { method: 'POST', credentials: 'same-origin', signal: controller.signal,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', ...csrfHeaders, 'Idempotency-Key': originalKey }, body: serialized })
    let payload: unknown
    try { payload = readTomJson(new TextDecoder('utf-8', { fatal: true }).decode(await response.arrayBuffer())) }
    catch { throw invalidResponse(response.status, controller.signal.aborted) }
    if (!response.ok) {
      let error: TomApiError
      try { error = parseError(payload, response.status) } catch { throw invalidResponse(response.status, controller.signal.aborted) }
      if (response.status === 403) { try { await refreshCsrf(signal) } catch { /* No implicit acceptance retry. */ } }
      throw error
    }
    try {
      if (response.status !== 200 || !exact(payload, ['code', 'message', 'data', 'traceId']) || payload.code !== 'OK' || typeof payload.message !== 'string' || !text(payload.traceId, 200)) throw new Error('Invalid acceptance envelope')
      const data = parseTomOrder(payload.data), replayed = response.headers.get('Idempotency-Replayed')
      if (data.id.toLowerCase() !== orderId || data.lifecycleStatus !== 'OPEN' || data.acceptanceStatus !== 'ACCEPTED' || data.version !== expectedVersion + 1 || !(replayed === 'true' || replayed === 'false')) throw new Error('Invalid acceptance acknowledgement')
      return { code: 'OK', message: payload.message, data, traceId: payload.traceId, replayed: replayed === 'true', location: response.headers.get('Location') }
    } catch { throw invalidResponse(response.status, controller.signal.aborted) }
  } catch (failure) {
    if (failure instanceof TomApiError) throw failure
    throw new TomApiError(0, controller.signal.aborted ? 'REQUEST_ABORTED' : 'NETWORK_ERROR', controller.signal.aborted ? '受理連線逾時或中斷；請確認原次受理結果。' : '無法確認受理回應，請確認網路後以原內容重試。', { uncertain: true })
  } finally { window.clearTimeout(timeout); signal?.removeEventListener('abort', abort) }
}
