import { ApiError, getCsrfHeaders, refreshCsrf } from './api'

export const orderTypes = ['IMPORT', 'EXPORT', 'LOCAL'] as const
export type OrderType = typeof orderTypes[number]
export type ItemKind = 'CONTAINER' | 'CARGO'
export type OrderSort = 'updatedAt,desc' | 'updatedAt,asc' | 'orderNo,asc' | 'orderNo,desc'
/** Unbounded page/count metadata uses an exact decimal string above JavaScript's safe integer range. */
export type ExactInteger = number | string
export interface ShippingDraft {
  shippingCompany?: string | null; vesselVoyage?: string | null; pol?: string | null; pod?: string | null
  eta?: string | null; etd?: string | null; blNo?: string | null
}
export type Shipping = Required<ShippingDraft>
export interface SiteContactDraft { name?: string | null; phone?: string | null }
export type SiteContactRead = Required<SiteContactDraft>
export interface OrderItemDraft {
  clientRef: string; sequence?: number | null; itemKind?: ItemKind | null; containerType?: string | null; containerNo?: string | null
  packageQty?: number | null; grossWeight?: string | null; cbm?: string | null; lastPickupDate?: string | null; deliveryCutoffAt?: string | null
}
export interface TransportTaskDraft {
  clientRef: string; itemRef: string; taskKind?: string | null; pickupName?: string | null; pickupAddress?: string | null
  deliveryName?: string | null; deliveryAddress?: string | null; siteContact?: SiteContactDraft | null
  plannedStartAt?: string | null; plannedEndAt?: string | null; predecessorRefs?: string[]; requiredTask?: true | null
}
export interface OrderDraft {
  type: OrderType; customerId: string; customerReference?: string | null; remark?: string | null
  shipping?: ShippingDraft | null; items?: OrderItemDraft[]; tasks?: TransportTaskDraft[]
}
export interface OrderItem extends Required<OrderItemDraft> { id: string }
export interface TransportTask extends Omit<Required<TransportTaskDraft>, 'siteContact'> {
  id: string; taskNo: string; orderId: string; itemId: string; predecessorTaskIds: string[]; siteContact: SiteContactRead | null
}
export interface OrderSummary {
  id: string; orderNo: string; type: OrderType; customerId: string; customerName: string
  lifecycleStatus: 'OPEN' | 'CANCELLED' | 'CLOSED'; acceptanceStatus: 'DRAFT' | 'ACCEPTED'
  version: number; createdAt: string; updatedAt: string
}
export interface Order extends OrderSummary {
  customerReference: string | null; remark: string | null; shipping: Shipping | null
  items: OrderItem[]; tasks: TransportTask[]; createdBy: string; updatedBy: string
}
export interface ReplaceOrderDraft { expectedVersion: number; draft: OrderDraft }
export interface OrderListQuery { page?: ExactInteger; size?: number; sort?: OrderSort; type?: OrderType; customerId?: string }
export interface OrderPage { content: OrderSummary[]; page: ExactInteger; size: number; totalElements: ExactInteger; totalPages: ExactInteger }
export interface FieldIssue { path: string; fieldId: string; ruleId: string; message: string }
export interface AcceptanceCheck {
  orderId: string; version: number; checkedRules: ('ORD-P01' | 'ORD-P02' | 'ORD-P03')[]
  confirmedRulesOutcome: 'COMPLETE' | 'INCOMPLETE'; missingFields: FieldIssue[]; pendingPolicyIds: string[]; operationalAcceptance: 'NOT_GRANTED'
}
export interface TomSuccess<T> { code: 'OK'; message: string; data: T; traceId: string }
export interface TomWriteResult<T> extends TomSuccess<T> { replayed: boolean; location: string | null }
export const tomErrorCodes = ['VALIDATION_ERROR', 'INVALID_CUSTOMER', 'UNAUTHENTICATED', 'FORBIDDEN', 'CSRF_INVALID', 'ORDER_NOT_FOUND',
  'VERSION_CONFLICT', 'ORDER_NOT_EDITABLE', 'PROTECTED_RELATION', 'IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_IN_PROGRESS', 'INTERNAL_ERROR'] as const
export type TomErrorCode = typeof tomErrorCodes[number]
type ClientErrorCode = 'NETWORK_ERROR' | 'INVALID_RESPONSE' | 'REQUEST_ABORTED' | 'CLIENT_VALIDATION_ERROR'
interface ErrorDetails { traceId?: string | null; fieldErrors?: FieldIssue[]; actualVersion?: number; uncertain?: boolean }
export class TomApiError extends ApiError {
  readonly traceId: string | null
  readonly fieldErrors: FieldIssue[]
  readonly actualVersion: number | undefined
  readonly uncertain: boolean
  constructor(status: number, public code: TomErrorCode | ClientErrorCode, message: string, details: ErrorDetails = {}) {
    super(status, message, Object.fromEntries((details.fieldErrors ?? []).map((issue) => [issue.path, issue.message])))
    this.name = 'TomApiError'; this.traceId = details.traceId ?? null; this.fieldErrors = details.fieldErrors ?? []
    this.actualVersion = details.actualVersion; this.uncertain = details.uncertain ?? false
  }
}
// Contract-name aliases keep editor models independent of transport naming.
export type DraftData = OrderDraft
export type OrderDraftRead = Order
export type ItemDraftInput = OrderItemDraft
export type TaskDraftInput = TransportTaskDraft
export type ShippingFields = ShippingDraft
export type SiteContact = SiteContactDraft

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const refPattern = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/
const decimalPattern = /^(0|[1-9][0-9]*)(\.[0-9]+)?$/
const datetimePattern = /^([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$/
const maxVersion = Number.MAX_SAFE_INTEGER
function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function exact(value: unknown, fields: readonly string[]): value is Record<string, unknown> {
  return record(value) && Object.keys(value).length === fields.length && fields.every((field) => Object.hasOwn(value, field))
}
function full(pattern: RegExp, value: string): boolean { return pattern.exec(value)?.[0] === value }
function text(value: unknown, maximum: number, blank = false): value is string {
  return typeof value === 'string' && [...value].length <= maximum && (blank || /\S/u.test(value))
}
function uuid(value: unknown): value is string { return typeof value === 'string' && value.length === 36 && full(uuidPattern, value) }
function ref(value: unknown): value is string { return typeof value === 'string' && full(refPattern, value) }
function safeInteger(value: unknown, minimum = 0, maximum = maxVersion): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
}
class WireInteger { constructor(readonly decimal: string) {} }
function metadataInteger(value: unknown): value is number | WireInteger { return safeInteger(value) || value instanceof WireInteger && BigInt(value.decimal) >= 0n }
function metadata(value: number | WireInteger): ExactInteger { return value instanceof WireInteger ? value.decimal : value }
function date(value: unknown): value is string {
  if (typeof value !== 'string' || !full(/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/, value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1]
}
function datetime(value: unknown): value is string { return typeof value === 'string' && full(datetimePattern, value) && date(value.slice(0, 10)) }
function nullable<T>(value: unknown, check: (value: unknown) => value is T): value is T | null { return value === null || check(value) }
function strings(value: unknown): value is string[] { return Array.isArray(value) && value.every((item) => typeof item === 'string') }
function unique(values: readonly string[]): boolean { return new Set(values).size === values.length }
function malformed(): never { throw new Error('Invalid TOM payload') }
function issues(value: unknown): FieldIssue[] {
  if (!Array.isArray(value) || !value.every((issue) => exact(issue, ['path', 'fieldId', 'ruleId', 'message']) && Object.values(issue).every((field) => typeof field === 'string'))) malformed()
  return value as FieldIssue[]
}
function shipping(value: unknown): Shipping | null {
  if (value === null) return null
  const fields = ['shippingCompany', 'vesselVoyage', 'pol', 'pod', 'eta', 'etd', 'blNo']
  if (!exact(value, fields) || !['shippingCompany', 'vesselVoyage', 'pol', 'pod', 'blNo'].every((field) => nullable(value[field], (v): v is string => text(v, 200))) || !nullable(value.eta, datetime) || !nullable(value.etd, datetime)) malformed()
  return value as unknown as Shipping
}
function item(value: unknown): OrderItem {
  if (!exact(value, ['id', 'clientRef', 'sequence', 'itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm', 'lastPickupDate', 'deliveryCutoffAt']) ||
    !uuid(value.id) || !ref(value.clientRef) || !nullable(value.sequence, (v): v is number => safeInteger(v, 1, 2147483647)) ||
    !(value.itemKind === null || value.itemKind === 'CONTAINER' || value.itemKind === 'CARGO') ||
    !['containerType', 'containerNo'].every((field) => nullable(value[field], (v): v is string => text(v, 200))) ||
    !nullable(value.packageQty, (v): v is number => safeInteger(v, 1, 2147483647)) ||
    !['grossWeight', 'cbm'].every((field) => nullable(value[field], (v): v is string => typeof v === 'string' && v.length <= 32 && full(decimalPattern, v))) ||
    !nullable(value.lastPickupDate, date) || !nullable(value.deliveryCutoffAt, datetime)) malformed()
  return value as unknown as OrderItem
}
function task(value: unknown): TransportTask {
  if (!exact(value, ['id', 'taskNo', 'orderId', 'itemId', 'clientRef', 'itemRef', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'predecessorRefs', 'requiredTask', 'predecessorTaskIds']) ||
    !['id', 'orderId', 'itemId'].every((field) => uuid(value[field])) || !text(value.taskNo, 100) || !ref(value.clientRef) || !ref(value.itemRef) ||
    !['taskKind', 'pickupName', 'deliveryName'].every((field) => nullable(value[field], (v): v is string => text(v, 200))) ||
    !['pickupAddress', 'deliveryAddress'].every((field) => nullable(value[field], (v): v is string => text(v, 1000))) ||
    !nullable(value.plannedStartAt, datetime) || !nullable(value.plannedEndAt, datetime) ||
    !(value.requiredTask === true || value.requiredTask === null) || !strings(value.predecessorRefs) || !value.predecessorRefs.every(ref) || !unique(value.predecessorRefs) ||
    !strings(value.predecessorTaskIds) || !value.predecessorTaskIds.every(uuid) || !unique(value.predecessorTaskIds)) malformed()
  if (value.siteContact !== null && (!exact(value.siteContact, ['name', 'phone']) || !nullable(value.siteContact.name, (v): v is string => text(v, 200)) || !nullable(value.siteContact.phone, (v): v is string => text(v, 50)))) malformed()
  return value as unknown as TransportTask
}
const summaryFields = ['id', 'orderNo', 'type', 'customerId', 'customerName', 'lifecycleStatus', 'acceptanceStatus', 'version', 'createdAt', 'updatedAt']
function summaryFieldsValid(value: Record<string, unknown>): boolean {
  return uuid(value.id) && text(value.orderNo, 100) && orderTypes.includes(value.type as OrderType) && uuid(value.customerId) && text(value.customerName, 200) &&
    ['OPEN', 'CANCELLED', 'CLOSED'].includes(value.lifecycleStatus as string) && ['DRAFT', 'ACCEPTED'].includes(value.acceptanceStatus as string) && safeInteger(value.version) && datetime(value.createdAt) && datetime(value.updatedAt)
}
function summary(value: unknown): OrderSummary { if (!exact(value, summaryFields) || !summaryFieldsValid(value)) malformed(); return value as unknown as OrderSummary }
function order(value: unknown): Order {
  if (!exact(value, [...summaryFields, 'customerReference', 'remark', 'shipping', 'items', 'tasks', 'createdBy', 'updatedBy']) || !summaryFieldsValid(value) ||
    !nullable(value.customerReference, (v): v is string => text(v, 200)) || !nullable(value.remark, (v): v is string => text(v, 4000, true)) || !uuid(value.createdBy) || !uuid(value.updatedBy) || !Array.isArray(value.items) || !Array.isArray(value.tasks)) malformed()
  const result = { ...value, shipping: shipping(value.shipping), items: value.items.map(item), tasks: value.tasks.map(task) } as unknown as Order
  if (!unique(result.items.map((v) => v.clientRef)) || !unique(result.items.map((v) => v.id)) || !unique(result.tasks.map((v) => v.clientRef)) || !unique(result.tasks.map((v) => v.id))) malformed()
  const itemByRef = new Map(result.items.map((v) => [v.clientRef, v]))
  const taskByRef = new Map(result.tasks.map((v) => [v.clientRef, v]))
  for (const current of result.tasks) {
    if (current.orderId !== result.id || itemByRef.get(current.itemRef)?.id !== current.itemId || current.predecessorRefs.length !== current.predecessorTaskIds.length ||
      current.predecessorRefs.some((predecessor) => predecessor === current.clientRef || !current.predecessorTaskIds.includes(taskByRef.get(predecessor)?.id ?? ''))) malformed()
  }
  return result
}
function page(value: unknown): OrderPage {
  if (!exact(value, ['content', 'page', 'size', 'totalElements', 'totalPages']) || !Array.isArray(value.content) || !metadataInteger(value.page) || !safeInteger(value.size, 1, 100) || !metadataInteger(value.totalElements) || !metadataInteger(value.totalPages)) malformed()
  return { content: value.content.map(summary), page: metadata(value.page), size: value.size,
    totalElements: metadata(value.totalElements), totalPages: metadata(value.totalPages) }
}
function acceptance(value: unknown): AcceptanceCheck {
  if (!exact(value, ['orderId', 'version', 'checkedRules', 'confirmedRulesOutcome', 'missingFields', 'pendingPolicyIds', 'operationalAcceptance']) || !uuid(value.orderId) || !safeInteger(value.version) ||
    !strings(value.checkedRules) || value.checkedRules.length !== 3 || !['ORD-P01', 'ORD-P02', 'ORD-P03'].every((rule) => (value.checkedRules as string[]).includes(rule)) ||
    !(value.confirmedRulesOutcome === 'COMPLETE' || value.confirmedRulesOutcome === 'INCOMPLETE') || !strings(value.pendingPolicyIds) || !unique(value.pendingPolicyIds) || value.operationalAcceptance !== 'NOT_GRANTED') malformed()
  const missing = issues(value.missingFields)
  if ((value.confirmedRulesOutcome === 'COMPLETE') !== (missing.length === 0)) malformed()
  return value as unknown as AcceptanceCheck
}

/** Small JSON reader: detect duplicates and preserve unbounded integer metadata without double rounding. */
function readJson(raw: string): unknown {
  let index = 0
  const whitespace = () => { while (/[\x20\t\r\n]/.test(raw[index] ?? '\uFFFF')) index++ }
  function stringValue(): string {
    const begin = index++
    while (index < raw.length) {
      const char = raw[index++]
      if (char === '\\') { index++; continue }
      if (char === '"') {
        const value: unknown = JSON.parse(raw.slice(begin, index))
        if (typeof value !== 'string') malformed()
        for (let i = 0; i < value.length; i++) {
          const code = value.charCodeAt(i)
          if (code >= 0xD800 && code <= 0xDBFF) { const next = value.charCodeAt(++i); if (!(next >= 0xDC00 && next <= 0xDFFF)) malformed() }
          else if (code >= 0xDC00 && code <= 0xDFFF) malformed()
        }
        return value
      }
    }
    return malformed()
  }
  function value(): unknown {
    whitespace(); const char = raw[index]
    if (char === '"') return stringValue()
    if (char === '{') {
      index++; whitespace(); const result: Record<string, unknown> = Object.create(null)
      if (raw[index] === '}') { index++; return result }
      while (true) {
        whitespace(); if (raw[index] !== '"') malformed(); const key = stringValue()
        if (Object.hasOwn(result, key)) malformed(); whitespace(); if (raw[index++] !== ':') malformed(); result[key] = value(); whitespace()
        if (raw[index] === '}') { index++; return result }; if (raw[index++] !== ',') malformed()
      }
    }
    if (char === '[') {
      index++; whitespace(); const result: unknown[] = []
      if (raw[index] === ']') { index++; return result }
      while (true) { result.push(value()); whitespace(); if (raw[index] === ']') { index++; return result }; if (raw[index++] !== ',') malformed() }
    }
    for (const [literal, parsed] of [['true', true], ['false', false], ['null', null]] as const) if (raw.startsWith(literal, index)) { index += literal.length; return parsed }
    const token = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(raw.slice(index))?.[0]
    if (!token) malformed(); index += token.length
    const match = /^(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(token)!
    let integer = BigInt(match[2] + (match[3] ?? '')) * (match[1] ? -1n : 1n)
    const scale = BigInt(match[4] ?? '0') - BigInt((match[3] ?? '').length)
    if (integer !== 0n && scale < 0n) {
      if (-scale > BigInt((match[2] + (match[3] ?? '')).length)) return { invalidNonInteger: token }
      const divisor = 10n ** -scale
      if (integer % divisor !== 0n) return { invalidNonInteger: token }
      integer /= divisor
    } else if (integer !== 0n) {
      // Never expand a tiny exponent token into an unbounded allocation. Plain
      // decimal integer tokens remain exact at any length supplied by the server.
      if (scale > BigInt(Math.max(4096, token.length))) malformed()
      integer *= 10n ** scale
    }
    return integer <= BigInt(maxVersion) && integer >= -BigInt(maxVersion) ? Number(integer) : new WireInteger(integer.toString())
  }
  const result = value(); whitespace(); if (index !== raw.length) malformed(); return result
}

function validateJson(value: unknown, stack = new Set<object>()): void {
  if (value === null || value === undefined || typeof value === 'string' || typeof value === 'boolean') return
  if (typeof value === 'number') { if (!Number.isSafeInteger(value)) throw new Error('Unsafe JSON integer'); return }
  if (typeof value !== 'object' || stack.has(value)) throw new Error('Invalid JSON value')
  stack.add(value)
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) { if (!(i in value) || value[i] === undefined) throw new Error('Sparse JSON array'); validateJson(value[i], stack) }
  } else {
    if (![Object.prototype, null].includes(Object.getPrototypeOf(value))) throw new Error('Non-JSON object')
    for (const field of Object.values(value)) validateJson(field, stack)
  }
  stack.delete(value)
}
function invalidInput(message: string): never { throw new TomApiError(400, 'CLIENT_VALIDATION_ERROR', message) }
function path(id: string): string { if (!uuid(id)) invalidInput('訂單識別格式不正確。'); return `/orders/${encodeURIComponent(id)}` }
function serialize(body: unknown): string { try { validateJson(body); return JSON.stringify(body) } catch { return invalidInput('草稿含有無法原樣傳送的數值或資料，請確認內容。') } }
const statusForCode: Record<TomErrorCode, number> = { VALIDATION_ERROR: 400, INVALID_CUSTOMER: 400, UNAUTHENTICATED: 401, FORBIDDEN: 403, CSRF_INVALID: 403, ORDER_NOT_FOUND: 404, VERSION_CONFLICT: 409, ORDER_NOT_EDITABLE: 409, PROTECTED_RELATION: 409, IDEMPOTENCY_KEY_REUSED: 409, IDEMPOTENCY_IN_PROGRESS: 409, INTERNAL_ERROR: 500 }
function serverError(payload: unknown, status: number): TomApiError {
  if (!exact(payload, ['code', 'message', 'data', 'traceId', 'fieldErrors']) || !tomErrorCodes.includes(payload.code as TomErrorCode) || statusForCode[payload.code as TomErrorCode] !== status || !text(payload.message, 2000) || !text(payload.traceId, 200)) malformed()
  const fieldErrors = issues(payload.fieldErrors)
  let actualVersion: number | undefined
  if (payload.data !== null) {
    if (!record(payload.data) || Object.keys(payload.data).some((field) => field !== 'actualVersion') || Object.hasOwn(payload.data, 'actualVersion') && !safeInteger(payload.data.actualVersion)) malformed()
    actualVersion = payload.data.actualVersion as number | undefined
  }
  const message = status === 401 ? '登入狀態已失效，請重新登入。' : status >= 500 ? '服務暫時無法使用，請稍後重試。' : payload.message as string
  return new TomApiError(status, payload.code as TomErrorCode, message, { traceId: payload.traceId as string, fieldErrors, actualVersion })
}

async function transport<T>(url: string, parse: (value: unknown) => T, method: 'GET' | 'POST' | 'PUT', signal?: AbortSignal, serialized?: string, key?: string): Promise<TomSuccess<T> | TomWriteResult<T>> {
  const writing = method !== 'GET'
  if (writing && (typeof key !== 'string' || !full(/^[A-Za-z0-9._:-]{1,128}$/, key))) invalidInput('請提供明確有效的重送識別。')
  if (signal?.aborted) throw new TomApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  let csrfHeaders: Record<string, string> = {}
  if (writing) {
    try { csrfHeaders = await getCsrfHeaders(signal) } catch (error) {
      if (signal?.aborted) throw new TomApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
      if (error instanceof ApiError) throw new TomApiError(error.status, error.status === 401 ? 'UNAUTHENTICATED' : 'NETWORK_ERROR', error.message)
      throw new TomApiError(0, 'NETWORK_ERROR', '無法取得安全驗證，請稍後重試。')
    }
  }
  if (signal?.aborted) throw new TomApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  const controller = new AbortController(); const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timeout = window.setTimeout(abort, 10000)
  let response: Response
  try {
    response = await fetch(`/api/v1${url}`, { method, credentials: 'same-origin', signal: controller.signal,
      headers: { Accept: 'application/json', ...(writing ? { 'Content-Type': 'application/json', ...csrfHeaders, 'Idempotency-Key': key! } : {}) },
      ...(writing ? { body: serialized } : {}) })
    let payload: unknown
    try { payload = readJson(new TextDecoder('utf-8', { fatal: true }).decode(await response.arrayBuffer())) } catch {
      throw new TomApiError(response.status, controller.signal.aborted ? 'REQUEST_ABORTED' : 'INVALID_RESPONSE', controller.signal.aborted ? '連線逾時或中斷；請確認原次保存結果。' : '訂單服務回應格式不正確，請重新確認。', { uncertain: writing })
    }
    if (!response.ok) {
      let error: TomApiError
      try { error = serverError(payload, response.status) } catch { throw new TomApiError(response.status, 'INVALID_RESPONSE', '訂單服務回應格式不正確，請重新確認。', { uncertain: writing }) }
      if (response.status === 403) { try { await refreshCsrf(signal) } catch { /* The next explicit write reacquires a token. */ } }
      if (writing && response.status >= 500) throw new TomApiError(error.status, error.code, error.message, {
        traceId: error.traceId, fieldErrors: error.fieldErrors, actualVersion: error.actualVersion, uncertain: true,
      })
      throw error
    }
    try {
      if (response.status !== (method === 'POST' ? 201 : 200) || !exact(payload, ['code', 'message', 'data', 'traceId']) || payload.code !== 'OK' || typeof payload.message !== 'string' || !text(payload.traceId, 200)) malformed()
      const result: TomSuccess<T> = { code: 'OK', message: payload.message as string, data: parse(payload.data), traceId: payload.traceId as string }
      if (!writing) return result
      const replayed = response.headers.get('Idempotency-Replayed'); const location = response.headers.get('Location')
      if (!(replayed === 'true' || replayed === 'false') || method === 'POST' && !location) malformed()
      return { ...result, replayed: replayed === 'true', location }
    } catch { throw new TomApiError(response.status, 'INVALID_RESPONSE', '訂單服務回應格式不正確，請重新確認。', { uncertain: writing }) }
  } catch (error) {
    if (error instanceof TomApiError) throw error
    throw new TomApiError(0, controller.signal.aborted ? 'REQUEST_ABORTED' : 'NETWORK_ERROR', controller.signal.aborted ? '連線逾時或中斷；請確認原次保存結果。' : '無法確認服務回應，請確認網路後重試。', { uncertain: writing })
  } finally { window.clearTimeout(timeout); signal?.removeEventListener('abort', abort) }
}

export async function getOrders(query: OrderListQuery = {}, signal?: AbortSignal): Promise<TomSuccess<OrderPage>> {
  if (Object.keys(query).some((field) => !['page', 'size', 'sort', 'type', 'customerId'].includes(field))) invalidInput('不支援此訂單篩選條件。')
  const pageValue = query.page ?? 0
  if (!(safeInteger(pageValue) || typeof pageValue === 'string' && /^[+-]?[0-9]+$/.test(pageValue) && BigInt(pageValue) >= 0n)) invalidInput('頁碼須為非負整數。')
  if (!safeInteger(query.size ?? 20, 1, 100)) invalidInput('每頁筆數須為 1 至 100。')
  if (query.sort !== undefined && !['updatedAt,desc', 'updatedAt,asc', 'orderNo,asc', 'orderNo,desc'].includes(query.sort)) invalidInput('不支援此排序條件。')
  if (query.type !== undefined && !orderTypes.includes(query.type)) invalidInput('訂單類型不正確。')
  if (query.customerId !== undefined && !uuid(query.customerId)) invalidInput('客戶識別格式不正確。')
  const params = new URLSearchParams({ page: String(pageValue), size: String(query.size ?? 20), sort: query.sort ?? 'updatedAt,desc' })
  if (query.type !== undefined) params.set('type', query.type)
  if (query.customerId !== undefined) params.set('customerId', query.customerId)
  return transport(`/orders?${params}`, page, 'GET', signal)
}
export async function getOrder(id: string, signal?: AbortSignal): Promise<TomSuccess<Order>> { return transport(path(id), order, 'GET', signal) }
export async function createOrder(draft: OrderDraft, key: string, signal?: AbortSignal): Promise<TomWriteResult<Order>> {
  return transport('/orders', order, 'POST', signal, serialize(draft), key) as Promise<TomWriteResult<Order>>
}
export function replaceOrder(id: string, body: ReplaceOrderDraft, key: string, signal?: AbortSignal): Promise<TomWriteResult<Order>>
export function replaceOrder(id: string, expectedVersion: number, draft: OrderDraft, key: string, signal?: AbortSignal): Promise<TomWriteResult<Order>>
export async function replaceOrder(id: string, value: number | ReplaceOrderDraft, draftOrKey: OrderDraft | string, keyOrSignal?: string | AbortSignal, signal?: AbortSignal): Promise<TomWriteResult<Order>> {
  const body = typeof value === 'number' ? { expectedVersion: value, draft: draftOrKey as OrderDraft } : value
  const key = typeof value === 'number' ? keyOrSignal as string : draftOrKey as string
  const abortSignal = typeof value === 'number' ? signal : keyOrSignal as AbortSignal | undefined
  return transport(path(id), order, 'PUT', abortSignal, serialize(body), key) as Promise<TomWriteResult<Order>>
}
export async function getAcceptanceCheck(id: string, signal?: AbortSignal): Promise<TomSuccess<AcceptanceCheck>> { return transport(`${path(id)}/acceptance-check`, acceptance, 'GET', signal) }
export const checkOrder = getAcceptanceCheck
export function newIdempotencyKey(): string { return crypto.randomUUID() }
// Pure legacy parsers shared by the independent acceptance operation.
export { order as parseTomOrder, readJson as readTomJson }
