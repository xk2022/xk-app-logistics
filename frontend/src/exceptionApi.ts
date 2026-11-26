import { ApiError, getCsrfHeaders, refreshCsrf } from './api'
import { readTomJson } from './tomApi'

export type ExceptionStatus = 'OPEN' | 'NEEDS_SUPPLEMENT' | 'RESOLVED'
export type ReportedPhase = 'DEPARTED' | 'IN_PROGRESS' | 'AWAITING_COMPLETION'
export type ExecutionPhase = 'WAITING' | ReportedPhase | 'COMPLETED'
export type ExceptionMode = 'UNCONFIRMED' | 'SYNTHETIC_TEST_ONLY'
export interface ExecutionView { phase: ExecutionPhase; version: number; departedAt: string | null }
export interface ExceptionSummary { id: string; assignmentId: string; taskId: string; taskNo: string; reportedPhase: ReportedPhase; status: ExceptionStatus; version: number; blocking: boolean; reportedAt: string }
export interface ActiveSupplementRequest { id: string; reason: string; requestedAt: string; uploadedCount: number; evidenceIds: string[] }
export interface ExceptionRecord extends ExceptionSummary { reportedExecutionVersion: number; description: string; resolvedAt: string | null; resolutionNote: string | null; activeRequest: ActiveSupplementRequest | null }
export type ActionBlocker = 'FORBIDDEN' | 'POLICY_NOT_CONFIRMED' | 'INVALID_STATE' | 'SUPPLEMENT_REQUIRED' | 'EVIDENCE_REQUIRED'
export interface ExceptionAction { allowed: boolean; blocker: ActionBlocker | null }
export interface SupplementRequirements { minimumCount: 1; allowedMediaTypes: ['image/png']; maxBytes: 65536; maxPixels: 4096 }
export interface ExceptionActions { mode: ExceptionMode; requestSupplement: ExceptionAction; resolve: ExceptionAction; uploadSupplement: ExceptionAction; submitSupplement: ExceptionAction; requirements: SupplementRequirements | null }
export interface ExceptionDetail { exception: ExceptionRecord; execution: ExecutionView; actions: ExceptionActions }
export interface ExceptionPage<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type ExceptionEventType = 'REPORTED' | 'SUPPLEMENT_REQUESTED' | 'SUPPLEMENT_EVIDENCE_UPLOADED' | 'SUPPLEMENT_SUBMITTED' | 'RESOLVED'
export interface ManagerExceptionEvent { id: string; exceptionId: string; type: ExceptionEventType; fromStatus: ExceptionStatus | null; toStatus: ExceptionStatus; fromVersion: number | null; toVersion: number; occurredAt: string; note: string | null; requestId: string | null; evidenceIds: string[]; actor: { role: 'ADMIN' | 'DISPATCHER' | 'DRIVER'; displayName: string } }
export interface ExceptionEvidence { id: string; assignmentId: string; taskId: string; phase: ExecutionPhase; executionVersion: number; mediaType: 'image/png'; byteSize: number; sha256: string; uploadedAt: string; status: 'VERIFIED' }
export interface SupplementEvidence { requestId: string; submitted: boolean; evidence: ExceptionEvidence }
export interface ExceptionSuccess<T> { code: '0000'; message: string; data: T; timestamp: string }
export interface ExceptionWriteResult extends ExceptionSuccess<ExceptionRecord> { replayed: boolean }
export interface ExceptionListQuery { page?: number; size?: number; status?: ExceptionStatus; assignmentId?: string }
export interface ExceptionPageQuery { page?: number; size?: number }
export interface ExceptionReasonCommand { expectedVersion: number; reason: string }
export const exceptionBusinessReasons = { E1901: 'VERSION_CONFLICT', E1902: 'INVALID_STATE', E1903: 'POLICY_NOT_CONFIRMED', E1904: 'IDEMPOTENCY_KEY_REUSED', E1905: 'IDEMPOTENCY_IN_PROGRESS', E1906: 'PROTECTED_RELATION', E1907: 'SUPPLEMENT_REQUIRED', E1908: 'EVIDENCE_INVALID' } as const
export type ExceptionErrorCode = 'E0400' | 'E0401' | 'E0403' | 'E0404' | 'E0405' | 'E0413' | 'E0415' | 'E0500' | keyof typeof exceptionBusinessReasons | 'INVALID_RESPONSE' | 'CLIENT_VALIDATION_ERROR' | 'NETWORK_ERROR' | 'REQUEST_ABORTED'
export class ExceptionApiError extends ApiError {
  readonly reason: string | null
  readonly actualVersion?: number
  readonly actualExecutionVersion?: number
  readonly uncertain: boolean
  constructor(status: number, public code: ExceptionErrorCode, message: string, details: { reason?: string | null; actualVersion?: number; actualExecutionVersion?: number; fields?: Record<string, string>; uncertain?: boolean } = {}) {
    super(status, message, details.fields ?? {}); this.name = 'ExceptionApiError'; this.reason = details.reason ?? null; this.actualVersion = details.actualVersion; this.actualExecutionVersion = details.actualExecutionVersion; this.uncertain = details.uncertain ?? false
  }
}
const max = Number.MAX_SAFE_INTEGER
const statusValues = ['OPEN', 'NEEDS_SUPPLEMENT', 'RESOLVED'] as const
const phaseValues = ['WAITING', 'DEPARTED', 'IN_PROGRESS', 'AWAITING_COMPLETION', 'COMPLETED'] as const
const summaryKeys = ['id', 'assignmentId', 'taskId', 'taskNo', 'reportedPhase', 'status', 'version', 'blocking', 'reportedAt']
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function exact(value: unknown, keys: readonly string[]): value is Record<string, unknown> { return object(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key)) }
function full(pattern: RegExp, value: string) { return pattern.exec(value)?.[0] === value }
function uuid(value: unknown): value is string { return typeof value === 'string' && full(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, value) }
function canonical(value: unknown): value is string { return uuid(value) && value === value.toLowerCase() }
function integer(value: unknown, minimum = 0, upper = max): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= upper }
function member<T extends string>(value: unknown, values: readonly T[]): value is T { return values.includes(value as T) }
function unicode(value: string) { for (let index = 0; index < value.length; index++) { const code = value.charCodeAt(index); if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false } else if (code >= 0xdc00 && code <= 0xdfff) return false }; return true }
function text(value: unknown, limit: number, minimum = 0): value is string { return typeof value === 'string' && unicode(value) && [...value].length >= minimum && [...value].length <= limit }
export function nonblankExceptionText(value: string) { return [...value].some((char) => !(/[\p{White_Space}\p{Zs}]/u.test(char) && char !== '\u0085' || char === '\uFEFF' || char.charCodeAt(0) >= 0x1c && char.charCodeAt(0) <= 0x1f)) }
function fact(value: unknown, limit: number): value is string { return text(value, limit, 1) && nonblankExceptionText(value) }
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = /^(\d{4})-(\d{2})-(\d{2})[Tt]([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.[0-9]{1,9})?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1, 4).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
function nullable<T>(value: unknown, check: (entry: unknown) => entry is T): value is T | null { return value === null || check(value) }
function malformed(): never { throw new Error('Invalid exception response') }
function input(message: string, fields: Record<string, string> = {}): never { throw new ExceptionApiError(400, 'CLIENT_VALIDATION_ERROR', message, { reason: 'VALIDATION_ERROR', fields }) }
function identifiers(value: unknown): value is string[] { return Array.isArray(value) && value.length <= 32 && value.every(canonical) && value.every((id, index) => index === 0 || value[index - 1] < id) }
function parseSummary(value: unknown): ExceptionSummary {
  if (!exact(value, summaryKeys) || !['id', 'assignmentId', 'taskId'].every((key) => canonical(value[key])) || !text(value.taskNo, 100, 1) || !member(value.reportedPhase, ['DEPARTED', 'IN_PROGRESS', 'AWAITING_COMPLETION']) || !member(value.status, statusValues) || !integer(value.version) || value.version === 0 && value.status !== 'OPEN' || value.status === 'NEEDS_SUPPLEMENT' && value.version < 1 || value.blocking !== (value.status !== 'RESOLVED') || !timestamp(value.reportedAt)) malformed()
  return value as unknown as ExceptionSummary
}
function parseRequest(value: unknown): ActiveSupplementRequest {
  if (!exact(value, ['id', 'reason', 'requestedAt', 'uploadedCount', 'evidenceIds']) || !canonical(value.id) || !fact(value.reason, 1000) || !timestamp(value.requestedAt) || !integer(value.uploadedCount, 0, 32) || !identifiers(value.evidenceIds) || value.uploadedCount !== value.evidenceIds.length) malformed()
  return value as unknown as ActiveSupplementRequest
}
function parseRecord(value: unknown): ExceptionRecord {
  if (!exact(value, [...summaryKeys, 'reportedExecutionVersion', 'description', 'resolvedAt', 'resolutionNote', 'activeRequest'])) malformed()
  parseSummary(Object.fromEntries(summaryKeys.map((key) => [key, value[key]])))
  if (!integer(value.reportedExecutionVersion, 1) || !fact(value.description, 2000) || !nullable(value.resolvedAt, timestamp) || !nullable(value.resolutionNote, (entry): entry is string => fact(entry, 1000))) malformed()
  if (value.status === 'RESOLVED' ? value.resolvedAt === null || value.resolutionNote === null : value.resolvedAt !== null || value.resolutionNote !== null) malformed()
  if (value.status === 'NEEDS_SUPPLEMENT' ? value.activeRequest === null : value.activeRequest !== null) malformed()
  return { ...value, activeRequest: value.activeRequest === null ? null : parseRequest(value.activeRequest) } as unknown as ExceptionRecord
}
function parseExecution(value: unknown): ExecutionView {
  if (!exact(value, ['phase', 'version', 'departedAt']) || !member(value.phase, phaseValues) || !integer(value.version) || !nullable(value.departedAt, timestamp) || (value.phase === 'WAITING' ? value.version !== 0 || value.departedAt !== null : value.version < 1 || value.departedAt === null)) malformed()
  return value as unknown as ExecutionView
}
function parseAction(value: unknown): ExceptionAction {
  if (!exact(value, ['allowed', 'blocker']) || typeof value.allowed !== 'boolean' || !nullable(value.blocker, (entry): entry is ActionBlocker => member(entry, ['FORBIDDEN', 'POLICY_NOT_CONFIRMED', 'INVALID_STATE', 'SUPPLEMENT_REQUIRED', 'EVIDENCE_REQUIRED'])) || value.allowed !== (value.blocker === null)) malformed()
  return value as unknown as ExceptionAction
}
function parseDetail(value: unknown, expected: string): ExceptionDetail {
  if (!exact(value, ['exception', 'execution', 'actions']) || !exact(value.actions, ['mode', 'requestSupplement', 'resolve', 'uploadSupplement', 'submitSupplement', 'requirements']) || !member(value.actions.mode, ['UNCONFIRMED', 'SYNTHETIC_TEST_ONLY'])) malformed()
  const row = parseRecord(value.exception), execution = parseExecution(value.execution)
  const actions = { ...value.actions, requestSupplement: parseAction(value.actions.requestSupplement), resolve: parseAction(value.actions.resolve), uploadSupplement: parseAction(value.actions.uploadSupplement), submitSupplement: parseAction(value.actions.submitSupplement) } as unknown as ExceptionActions
  if (row.id !== expected || actions.uploadSupplement.allowed || actions.uploadSupplement.blocker !== 'FORBIDDEN' || actions.submitSupplement.allowed || actions.submitSupplement.blocker !== 'FORBIDDEN') malformed()
  for (const availability of [actions.requestSupplement, actions.resolve]) if (availability.allowed && (actions.mode !== 'SYNTHETIC_TEST_ONLY' || row.status !== 'OPEN')) malformed()
  const requirements = actions.requirements
  if (requirements !== null && (!exact(requirements, ['minimumCount', 'allowedMediaTypes', 'maxBytes', 'maxPixels']) || requirements.minimumCount !== 1 || !Array.isArray(requirements.allowedMediaTypes) || requirements.allowedMediaTypes.length !== 1 || requirements.allowedMediaTypes[0] !== 'image/png' || requirements.maxBytes !== 65536 || requirements.maxPixels !== 4096 || row.status !== 'NEEDS_SUPPLEMENT' || actions.mode !== 'SYNTHETIC_TEST_ONLY' || !['DEPARTED', 'IN_PROGRESS', 'AWAITING_COMPLETION'].includes(execution.phase))) malformed()
  return { exception: row, execution, actions }
}
function parseEvidence(value: unknown): ExceptionEvidence {
  if (!exact(value, ['id', 'assignmentId', 'taskId', 'phase', 'executionVersion', 'mediaType', 'byteSize', 'sha256', 'uploadedAt', 'status']) || !['id', 'assignmentId', 'taskId'].every((key) => canonical(value[key])) || !member(value.phase, phaseValues) || !integer(value.executionVersion, 1) || value.mediaType !== 'image/png' || !integer(value.byteSize, 1, 1048576) || typeof value.sha256 !== 'string' || !full(/^[0-9a-f]{64}$/, value.sha256) || !timestamp(value.uploadedAt) || value.status !== 'VERIFIED') malformed()
  return value as unknown as ExceptionEvidence
}
function parseEvent(value: unknown, expected: string): ManagerExceptionEvent {
  if (!exact(value, ['id', 'exceptionId', 'type', 'fromStatus', 'toStatus', 'fromVersion', 'toVersion', 'occurredAt', 'note', 'requestId', 'evidenceIds', 'actor']) || !canonical(value.id) || value.exceptionId !== expected || !member(value.type, ['REPORTED', 'SUPPLEMENT_REQUESTED', 'SUPPLEMENT_EVIDENCE_UPLOADED', 'SUPPLEMENT_SUBMITTED', 'RESOLVED']) || !nullable(value.fromStatus, (entry): entry is ExceptionStatus => member(entry, statusValues)) || !member(value.toStatus, statusValues) || !nullable(value.fromVersion, (entry): entry is number => integer(entry)) || !integer(value.toVersion) || !timestamp(value.occurredAt) || !nullable(value.note, (entry): entry is string => fact(entry, value.type === 'REPORTED' ? 2000 : 1000)) || !nullable(value.requestId, canonical) || !identifiers(value.evidenceIds) || !exact(value.actor, ['role', 'displayName']) || !member(value.actor.role, ['ADMIN', 'DISPATCHER', 'DRIVER']) || !text(value.actor.displayName, 80, 1)) malformed()
  const type = value.type
  if (type === 'REPORTED') { if (value.fromStatus !== null || value.fromVersion !== null || value.toStatus !== 'OPEN' || value.toVersion !== 0 || value.note === null || value.requestId !== null || value.evidenceIds.length) malformed() }
  else {
    if (value.fromVersion === null || value.toVersion !== value.fromVersion + 1 || ['SUPPLEMENT_EVIDENCE_UPLOADED', 'SUPPLEMENT_SUBMITTED'].includes(type) && (value.fromVersion < 1 || value.toVersion < 2) || (type === 'RESOLVED' ? value.requestId !== null : value.requestId === null)) malformed()
    if (type === 'SUPPLEMENT_REQUESTED' ? value.fromStatus !== 'OPEN' || value.toStatus !== 'NEEDS_SUPPLEMENT' || value.note === null || value.evidenceIds.length > 0 : type === 'SUPPLEMENT_EVIDENCE_UPLOADED' ? value.fromStatus !== 'NEEDS_SUPPLEMENT' || value.toStatus !== 'NEEDS_SUPPLEMENT' || value.note !== null || value.evidenceIds.length !== 1 : type === 'SUPPLEMENT_SUBMITTED' ? value.fromStatus !== 'NEEDS_SUPPLEMENT' || value.toStatus !== 'OPEN' || value.note === null || value.evidenceIds.length === 0 : value.fromStatus !== 'OPEN' || value.toStatus !== 'RESOLVED' || value.note === null || value.evidenceIds.length > 0) malformed()
  }
  if (['REPORTED', 'SUPPLEMENT_EVIDENCE_UPLOADED', 'SUPPLEMENT_SUBMITTED'].includes(type) ? value.actor.role !== 'DRIVER' : value.actor.role === 'DRIVER') malformed()
  return value as unknown as ManagerExceptionEvent
}
function parsePage<T>(value: unknown, parse: (entry: unknown) => T): ExceptionPage<T> {
  if (!exact(value, ['content', 'page', 'size', 'totalElements', 'totalPages']) || !Array.isArray(value.content) || !integer(value.page, 0, 100000) || !integer(value.size, 1, 100) || !integer(value.totalElements) || !integer(value.totalPages) || value.content.length > value.size) malformed()
  return { ...value, content: value.content.map(parse) } as unknown as ExceptionPage<T>
}
const errorStatuses: Record<string, number> = { E0400: 400, E0401: 401, E0403: 403, E0404: 404, E0405: 405, E0413: 413, E0415: 415, E0500: 500, ...Object.fromEntries(Object.keys(exceptionBusinessReasons).map((key) => [key, 409])) }
function parseError(value: unknown, status: number, writing: boolean): ExceptionApiError {
  if (!exact(value, ['code', 'message', 'data', 'timestamp']) || typeof value.code !== 'string' || errorStatuses[value.code] !== status || typeof value.message !== 'string' || !timestamp(value.timestamp) || !value.timestamp.endsWith('+08:00')) malformed()
  const data = value.data
  if (data !== null && (!object(data) || Object.keys(data).length === 0 || Object.keys(data).some((key) => !['reason', 'actualVersion', 'actualExecutionVersion', 'fields'].includes(key)) || Object.hasOwn(data, 'reason') && !text(data.reason, Infinity, 1) || Object.hasOwn(data, 'actualVersion') && !integer(data.actualVersion) || Object.hasOwn(data, 'actualExecutionVersion') && !integer(data.actualExecutionVersion) || Object.hasOwn(data, 'fields') && (!object(data.fields) || !Object.keys(data.fields).length || !Object.values(data.fields).every((message) => typeof message === 'string')))) malformed()
  if ([401, 404, 405, 413, 415, 500].includes(status) && data !== null || status === 403 && data !== null && (!exact(data, ['reason']) || !member(data.reason, ['CSRF_INVALID', 'FORBIDDEN'])) || status === 400 && (!object(data) || data.reason !== 'VALIDATION_ERROR' || !object(data.fields) || !Object.keys(data.fields).length)) malformed()
  if (Object.hasOwn(exceptionBusinessReasons, value.code) && (!object(data) || data.reason !== exceptionBusinessReasons[value.code as keyof typeof exceptionBusinessReasons])) malformed()
  if (value.code === 'E1901' && (!object(data) || Object.hasOwn(data, 'actualVersion') === Object.hasOwn(data, 'actualExecutionVersion'))) malformed()
  return new ExceptionApiError(status, value.code as ExceptionErrorCode, status === 401 ? '登入狀態已失效，請重新登入。' : status >= 500 ? '服務暫時無法使用，請確認原次操作。' : value.message, { ...data as object, uncertain: writing && (status >= 500 || ['E1904', 'E1905', 'E1906'].includes(value.code)) })
}
function protocol(status: number, writing: boolean, aborted = false) { return new ExceptionApiError(status, aborted ? 'REQUEST_ABORTED' : 'INVALID_RESPONSE', '異常服務回應無法確認，請重新讀取或確認原次操作。', { uncertain: writing }) }
async function readBytes(response: Response): Promise<Uint8Array<ArrayBuffer>> {
  if (!response.body) { const result = new Uint8Array(await response.arrayBuffer()); if (result.length > 1048576) malformed(); return result }
  const reader = response.body.getReader(), chunks: Uint8Array[] = []; let length = 0
  try { while (true) { const result = await reader.read(); if (result.done) break; length += result.value.byteLength; if (length > 1048576) { await reader.cancel(); malformed() }; chunks.push(result.value) } } finally { reader.releaseLock() }
  const result = new Uint8Array(length); let offset = 0; for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length }; return result
}
async function transport<T>(path: string, parse: (entry: unknown) => T, signal?: AbortSignal, operation?: { body: string; key: string }, photo?: ExceptionEvidence): Promise<ExceptionSuccess<T> & { replayed?: boolean }> {
  const writing = operation !== undefined
  if (signal?.aborted) throw new ExceptionApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  let csrf: Record<string, string> = {}
  if (writing) {
    try { csrf = await getCsrfHeaders(signal) }
    catch (failure) { if (failure instanceof ApiError && [401, 403].includes(failure.status)) throw new ExceptionApiError(failure.status, failure.status === 401 ? 'E0401' : 'E0403', '登入或存取狀態已失效，請重新確認。', { reason: failure.status === 403 ? 'FORBIDDEN' : null }); throw new ExceptionApiError(0, signal?.aborted ? 'REQUEST_ABORTED' : 'NETWORK_ERROR', '無法取得安全驗證，請確認連線後再試。') }
  }
  if (signal?.aborted) throw new ExceptionApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  const controller = new AbortController(), abort = () => controller.abort(); signal?.addEventListener('abort', abort, { once: true }); const timeout = window.setTimeout(abort, 10000)
  try {
    const response = await fetch(`/api/v1${path}`, { method: writing ? 'POST' : 'GET', credentials: 'same-origin', signal: controller.signal, headers: { Accept: photo ? 'image/png' : 'application/json', ...(operation ? { 'Content-Type': 'application/json', ...csrf, 'Idempotency-Key': operation.key } : {}) }, ...(operation ? { body: operation.body } : {}) })
    const wire = await readBytes(response)
    if (photo && response.status === 200) {
      const forbidden = ['ETag', 'Last-Modified', 'Accept-Ranges', 'Location', 'Content-Location'].some((name) => response.headers.has(name))
      if (response.headers.get('Content-Type') !== 'image/png' || response.headers.get('Content-Length') !== String(wire.length) || response.headers.get('Cache-Control') !== 'no-store, private' || response.headers.get('X-Content-Type-Options') !== 'nosniff' || response.headers.get('Content-Disposition') !== 'inline; filename="evidence.png"' || forbidden || wire.length !== photo.byteSize || wire.slice(0, 8).join(',') !== '137,80,78,71,13,10,26,10') throw protocol(response.status, false)
      const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', wire))].map((value) => value.toString(16).padStart(2, '0')).join('')
      if (digest !== photo.sha256) throw protocol(response.status, false)
      return { code: '0000', message: '成功', data: new Blob([wire], { type: 'image/png' }) as T, timestamp: '' }
    }
    let payload: unknown; try { payload = readTomJson(new TextDecoder('utf-8', { fatal: true }).decode(wire)) } catch { throw protocol(response.status, writing, controller.signal.aborted) }
    if (!response.ok) {
      let failure: ExceptionApiError; try { failure = parseError(payload, response.status, writing); if (writing && failure.code === 'E1901' && failure.actualVersion === undefined) throw new Error('Wrong command version unit') } catch { throw protocol(response.status, writing, controller.signal.aborted) }
      if (failure.status === 403 && failure.reason === 'CSRF_INVALID') { try { await refreshCsrf(signal) } catch (refreshFailure) { if (refreshFailure instanceof ApiError && [401, 403].includes(refreshFailure.status)) throw new ExceptionApiError(refreshFailure.status, refreshFailure.status === 401 ? 'E0401' : 'E0403', '登入或存取狀態已失效，請重新確認。', { reason: refreshFailure.status === 403 ? 'FORBIDDEN' : null, uncertain: writing }) } }
      throw failure
    }
    try {
      if (photo || response.status !== 200 || !exact(payload, ['code', 'message', 'data', 'timestamp']) || payload.code !== '0000' || typeof payload.message !== 'string' || !timestamp(payload.timestamp) || !payload.timestamp.endsWith('+08:00')) malformed()
      const result = parse(payload.data), replayed = response.headers.get('Idempotency-Replayed'); if (writing && !['true', 'false'].includes(replayed ?? '')) malformed()
      return { code: '0000', message: payload.message, timestamp: payload.timestamp, data: result, ...(writing ? { replayed: replayed === 'true' } : {}) }
    } catch { throw protocol(response.status, writing, controller.signal.aborted) }
  } catch (failure) { if (failure instanceof ExceptionApiError) throw failure; throw new ExceptionApiError(0, controller.signal.aborted ? 'REQUEST_ABORTED' : 'NETWORK_ERROR', '無法確認異常服務回應，請確認原次操作。', { uncertain: writing }) }
  finally { window.clearTimeout(timeout); signal?.removeEventListener('abort', abort) }
}
function identifier(value: string, field = 'id') { if (!uuid(value)) input('請提供完整識別。', { [field]: '識別格式不正確。' }); return value.toLowerCase() }
function params(query: ExceptionPageQuery, allowed: string[]): URLSearchParams { if (!object(query) || Object.keys(query).some((key) => !allowed.includes(key))) input('不支援此查詢條件。'); if (!integer(query.page ?? 0, 0, 100000) || !integer(query.size ?? 20, 1, 100)) input('頁碼或每頁筆數不正確。'); return new URLSearchParams({ page: String(query.page ?? 0), size: String(query.size ?? 20) }) }
export async function listExceptions(query: ExceptionListQuery = {}, signal?: AbortSignal): Promise<ExceptionPage<ExceptionSummary>> { const filter = { ...query }, search = params(filter, ['page', 'size', 'status', 'assignmentId']); if (filter.status !== undefined) { if (!member(filter.status, statusValues)) input('異常狀態不正確。'); search.set('status', filter.status) }; if (filter.assignmentId !== undefined) search.set('assignmentId', identifier(filter.assignmentId, 'assignmentId')); return (await transport(`/tms/exceptions?${search}`, (entry) => parsePage(entry, parseSummary), signal)).data }
export async function getException(id: string, signal?: AbortSignal): Promise<ExceptionDetail> { const target = identifier(id); return (await transport(`/tms/exceptions/${target}`, (entry) => parseDetail(entry, target), signal)).data }
export async function getExceptionHistory(id: string, query: ExceptionPageQuery = {}, signal?: AbortSignal): Promise<ExceptionPage<ManagerExceptionEvent>> { const target = identifier(id), search = params({ ...query }, ['page', 'size']); return (await transport(`/tms/exceptions/${target}/history?${search}`, (entry) => parsePage(entry, (row) => parseEvent(row, target)), signal)).data }
export async function getExceptionEvidence(id: string, query: ExceptionPageQuery = {}, signal?: AbortSignal): Promise<ExceptionPage<SupplementEvidence>> { const target = identifier(id), search = params({ ...query }, ['page', 'size']); return (await transport(`/tms/exceptions/${target}/evidence?${search}`, (entry) => parsePage(entry, (row) => { if (!exact(row, ['requestId', 'submitted', 'evidence']) || !canonical(row.requestId) || typeof row.submitted !== 'boolean') malformed(); return { requestId: row.requestId, submitted: row.submitted, evidence: parseEvidence(row.evidence) } }), signal)).data }
export async function getExceptionEvidenceContent(id: string, submitted: ExceptionEvidence, signal?: AbortSignal): Promise<Blob> { const target = identifier(id), photo = parseEvidence(structuredClone(submitted)); return (await transport<Blob>(`/tms/exceptions/${target}/evidence/${identifier(photo.id)}/content`, () => malformed(), signal, undefined, photo)).data }
const immutableFields = ['id', 'assignmentId', 'taskId', 'taskNo', 'reportedPhase', 'reportedExecutionVersion', 'description', 'reportedAt'] as const
async function managerCommand(mode: 'request' | 'resolve', id: string, submitted: ExceptionReasonCommand, submittedKey: string, original: ExceptionRecord, signal?: AbortSignal): Promise<ExceptionWriteResult> {
  const target = identifier(id)
  if (!exact(submitted, ['expectedVersion', 'reason']) || ![Object.prototype, null].includes(Object.getPrototypeOf(submitted)) || Reflect.ownKeys(submitted).length !== 2 || !integer(submitted.expectedVersion) || !fact(submitted.reason, 1000)) input('請提供有效異常版本及 1–1000 字元的非空白原因。', { reason: '請填寫原因，保留原文字元。' })
  if (typeof submittedKey !== 'string' || !full(/^[A-Za-z0-9._:-]{1,128}$/, submittedKey)) input('重送識別格式不正確。')
  let baseline: ExceptionRecord; try { baseline = parseRecord(structuredClone(original)) } catch { input('原異常資料無法確認，請重新讀取後再操作。') }; if (baseline.id !== target || baseline.version !== submitted.expectedVersion || baseline.status !== 'OPEN') input('原異常版本或識別不一致，請重新讀取。');
  const frozen = structuredClone(submitted), originalKey = submittedKey, path = `/tms/exceptions/${target}/${mode === 'request' ? 'supplement-requests' : 'resolve'}`, serialized = JSON.stringify(frozen)
  return await transport(path, (entry) => {
    const result = parseRecord(entry)
    if (result.id !== target || result.version !== frozen.expectedVersion + 1 || result.status !== (mode === 'request' ? 'NEEDS_SUPPLEMENT' : 'RESOLVED') || (mode === 'request' ? result.activeRequest?.reason !== frozen.reason : result.resolutionNote !== frozen.reason) || immutableFields.some((field) => result[field] !== baseline[field])) malformed()
    return result
  }, signal, { body: serialized, key: originalKey }) as ExceptionWriteResult
}
export function requestExceptionSupplement(id: string, submitted: ExceptionReasonCommand, submittedKey: string, original: ExceptionRecord, signal?: AbortSignal) { return managerCommand('request', id, submitted, submittedKey, original, signal) }
export function resolveException(id: string, submitted: ExceptionReasonCommand, submittedKey: string, original: ExceptionRecord, signal?: AbortSignal) { return managerCommand('resolve', id, submitted, submittedKey, original, signal) }
