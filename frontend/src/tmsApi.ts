import { ApiError, getCsrfHeaders, refreshCsrf } from './api'
import { readTomJson } from './tomApi'

export type PolicyMode = 'UNCONFIRMED' | 'SYNTHETIC_TEST_ONLY'
export type ResourcePosition = 'DRIVER' | 'MAIN' | 'TRAILER'
export type ResourceKind = 'DRIVER' | 'VEHICLE'
export type AssignmentStatus = 'ASSIGNED' | 'REVOKED' | 'SUPERSEDED'
export type Blocker = 'SOURCE_NOT_ACCEPTED' | 'PREREQUISITE_NOT_READY' | 'POLICY_NOT_CONFIRMED' | 'ALREADY_ASSIGNED' | 'ADMIN_UNAVAILABLE' | 'RESOURCE_OCCUPIED'
export interface TmsPage<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type PageData<T> = TmsPage<T>
export interface ItemSnapshot { itemKind: 'CONTAINER' | 'CARGO'; containerType: string | null; containerNo: string | null; packageQty: number | null; grossWeight: string | null; cbm: string | null }
export interface SiteContact { name: string | null; phone: string | null }
export interface TaskSummary { taskId: string; taskNo: string; orderId: string; orderNo: string; orderType: 'IMPORT' | 'EXPORT' | 'LOCAL'; orderVersion: number; generation: number; taskKind: string | null; pickupName: string | null; deliveryName: string | null; prerequisiteReady: boolean; currentAssignmentId: string | null; policyMode: PolicyMode; blockingReasons: Blocker[] }
export interface TaskSource { taskId: string; taskNo: string; orderId: string; orderNo: string; orderType: 'IMPORT' | 'EXPORT' | 'LOCAL'; orderVersion: number; item: ItemSnapshot; taskKind: string | null; pickupName: string | null; pickupAddress: string | null; deliveryName: string | null; deliveryAddress: string | null; siteContact: SiteContact | null; plannedStartAt: string | null; plannedEndAt: string | null; requiredTask: boolean | null }
export interface DriverSnapshot { id: string; driverCode: string; name: string; version: number }
export interface VehicleSnapshot { id: string; plateNo: string; vehicleType: string; vehicleTypeCode: string; vehicleTypeLabel: string; version: number }
export interface Reservation { id: string; kind: ResourceKind; position: ResourcePosition; resourceId: string; acquiredAt: string; releasedAt: string | null; releaseReason: string | null }
export interface DispatchEvent { id: string; action: 'ASSIGNED' | 'REVOKED' | 'REASSIGNED'; assignmentId: string; previousAssignmentId: string | null; fromGeneration: number; toGeneration: number; actorId: string; occurredAt: string; reason: string | null }
export interface Assignment { id: string; taskId: string; orderId: string; status: AssignmentStatus; version: 0 | 1; assignedGeneration: number; endedGeneration: number | null; driver: DriverSnapshot; mainVehicle: VehicleSnapshot; trailer: VehicleSnapshot | null; source: TaskSource; policyMode: PolicyMode; assignedBy: string; assignedAt: string; assignmentReason: string | null; endedBy: string | null; endedAt: string | null; endReason: string | null; previousAssignmentId: string | null; reservations: Reservation[]; events: DispatchEvent[] }
export interface DispatchDetail { task: TaskSummary; current: Assignment | null; history: TmsPage<Assignment> }
export interface CommandResult { taskId: string; generation: number; assignment: Assignment }
export interface ResourceCandidate { id: string; kind: ResourceKind; position: ResourcePosition; code: string; label: string; version: number; administrativelyAvailable: boolean; occupied: boolean; occupation: { assignmentId: string; taskId: string } | null; policyMode: PolicyMode; policyApproved: boolean; blockingReasons: Blocker[] }
export interface MyTask { assignmentId: string; taskId: string; taskNo: string; item: ItemSnapshot; taskKind: string | null; pickupName: string | null; pickupAddress: string | null; deliveryName: string | null; deliveryAddress: string | null; siteContact: SiteContact | null; plannedStartAt: string | null; plannedEndAt: string | null; assignedAt: string; policyMode: PolicyMode }
export interface TaskQuery { size?: number; state?: 'UNASSIGNED' | 'ASSIGNED' | 'ALL'; orderId?: string; q?: string }
export interface ResourceQuery { taskId: string; position: ResourcePosition; assignmentId?: string; page?: number; size?: number; q?: string }
export interface HistoryQuery { historyPage?: number; historySize?: number }
export interface MyTaskQuery { size?: number }
export interface AssignRequest { expectedGeneration: number; driverId: string; mainVehicleId: string; trailerId: string | null }
export interface RevokeRequest { expectedGeneration: number; reason: string }
export interface ReassignRequest extends AssignRequest { reason: string }
export interface DispatchCommandContext { taskId: string }
export interface TmsSuccess<T> { code: '0000'; message: string; data: T; timestamp: string }
export interface TmsWriteResult extends TmsSuccess<CommandResult> { replayed: boolean }
export const tmsBusinessReasons = { E1601: 'VERSION_CONFLICT', E1602: 'RESOURCE_OCCUPIED', E1603: 'INVALID_STATE', E1604: 'PREREQUISITE_NOT_READY', E1605: 'POLICY_NOT_CONFIRMED', E1606: 'MASTER_UNAVAILABLE', E1607: 'IDEMPOTENCY_KEY_REUSED', E1608: 'IDEMPOTENCY_IN_PROGRESS', E1609: 'PROTECTED_RELATION' } as const
type ServerCode = 'E0400' | 'E0401' | 'E0403' | 'E0404' | 'E0405' | 'E0415' | 'E0500' | keyof typeof tmsBusinessReasons
export type TmsErrorCode = ServerCode | 'CLIENT_VALIDATION_ERROR' | 'INVALID_RESPONSE' | 'NETWORK_ERROR' | 'REQUEST_ABORTED'
export interface TmsErrorDetails { reason?: string | null; actualGeneration?: number; fields?: Record<string, string>; uncertain?: boolean }
export class TmsApiError extends ApiError {
  readonly reason: string | null
  readonly actualGeneration: number | undefined
  readonly uncertain: boolean
  constructor(status: number, public code: TmsErrorCode, message: string, details: TmsErrorDetails = {}) {
    super(status, message, details.fields ?? {}); this.name = 'TmsApiError'; this.reason = details.reason ?? null; this.actualGeneration = details.actualGeneration; this.uncertain = details.uncertain ?? false
  }
}

const maximum = Number.MAX_SAFE_INTEGER
function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function exact(value: unknown, keys: readonly string[]): value is Record<string, unknown> { return record(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key)) }
function full(pattern: RegExp, value: string): boolean { return pattern.exec(value)?.[0] === value }
function uuid(value: unknown): value is string { return typeof value === 'string' && full(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, value) }
function sameId(a: string, b: string): boolean { return a.toLowerCase() === b.toLowerCase() }
function integer(value: unknown, minimum = 0, max = maximum): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= max }
function unicode(value: string): boolean {
  for (let index = 0; index < value.length; index++) { const code = value.charCodeAt(index); if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false } else if (code >= 0xdc00 && code <= 0xdfff) return false }
  return true
}
function text(value: unknown, max: number, min = 0): value is string { return typeof value === 'string' && unicode(value) && [...value].length >= min && [...value].length <= max }
function nullable<T>(value: unknown, check: (value: unknown) => value is T): value is T | null { return value === null || check(value) }
function member<T extends string>(value: unknown, choices: readonly T[]): value is T { return choices.includes(value as T) }
function malformed(): never { throw new Error('Invalid TMS payload') }
function input(message: string, fields: Record<string, string> = {}): never { throw new TmsApiError(400, 'CLIENT_VALIDATION_ERROR', message, { fields, reason: 'VALIDATION_ERROR' }) }
function date(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value); if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
function timestamp(value: unknown, fraction = 9): value is string {
  if (typeof value !== 'string') return false
  const match = /^(\d{4}-\d{2}-\d{2})[Tt]([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.(\d{1,9}))?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value)
  return Boolean(match && match[0] === value && date(match[1]) && (match[3]?.length ?? 0) <= fraction)
}
function companyTimestamp(value: unknown): value is string { return timestamp(value) && value.endsWith('+08:00') }
function policy(value: unknown): value is PolicyMode { return member(value, ['UNCONFIRMED', 'SYNTHETIC_TEST_ONLY']) }
function position(value: unknown): value is ResourcePosition { return member(value, ['DRIVER', 'MAIN', 'TRAILER']) }
function decimal(value: unknown): value is string { return typeof value === 'string' && value.length <= 32 && full(/^(0|[1-9][0-9]*)(\.[0-9]+)?$/, value) }
function strip(value: string): string {
  const boundary = (char: string) => char !== '\u0085' && /\p{White_Space}/u.test(char) || char === '\uFEFF' || char.charCodeAt(0) >= 0x1c && char.charCodeAt(0) <= 0x1f
  let start = 0, end = value.length; while (start < end && boundary(value[start])) start++; while (end > start && boundary(value[end - 1])) end--
  return value.slice(start, end)
}
function normalized(value: string): string { return strip(value.normalize('NFC')) }
function validReason(value: unknown): value is string {
  if (!text(value, 500, 1) || !/\S/u.test(value)) return false
  return ![...value].some((char) => { const code = char.codePointAt(0)!; return (code <= 0x1f || code >= 0x7f && code <= 0x9f) && ![9, 10, 13].includes(code) })
}
function blockers(value: unknown): value is Blocker[] {
  return Array.isArray(value) && value.length <= 6 && new Set(value).size === value.length && value.every((entry) => member(entry, ['SOURCE_NOT_ACCEPTED', 'PREREQUISITE_NOT_READY', 'POLICY_NOT_CONFIRMED', 'ALREADY_ASSIGNED', 'ADMIN_UNAVAILABLE', 'RESOURCE_OCCUPIED']))
}
function parseItem(value: unknown): ItemSnapshot {
  if (!exact(value, ['itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm']) || !member(value.itemKind, ['CONTAINER', 'CARGO']) || !['containerType', 'containerNo'].every((key) => nullable(value[key], (v): v is string => text(v, 200))) || !nullable(value.packageQty, (v): v is number => integer(v, 1, 2147483647)) || !nullable(value.grossWeight, decimal) || !nullable(value.cbm, decimal)) malformed()
  return value as unknown as ItemSnapshot
}
function parseContact(value: unknown): SiteContact | null { if (value === null) return null; if (!exact(value, ['name', 'phone']) || !nullable(value.name, (v): v is string => text(v, 200)) || !nullable(value.phone, (v): v is string => text(v, 200))) malformed(); return value as unknown as SiteContact }
function routeFields(value: Record<string, unknown>): boolean {
  return ['taskKind', 'pickupName', 'deliveryName'].every((key) => nullable(value[key], (v): v is string => text(v, 200))) && ['pickupAddress', 'deliveryAddress'].every((key) => nullable(value[key], (v): v is string => text(v, 1000))) && nullable(value.plannedStartAt, (v): v is string => timestamp(v, 6)) && nullable(value.plannedEndAt, (v): v is string => timestamp(v, 6))
}
function parseSource(value: unknown): TaskSource {
  if (!exact(value, ['taskId', 'taskNo', 'orderId', 'orderNo', 'orderType', 'orderVersion', 'item', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'requiredTask']) || !uuid(value.taskId) || !uuid(value.orderId) || !text(value.taskNo, 100, 1) || !text(value.orderNo, 100, 1) || !member(value.orderType, ['IMPORT', 'EXPORT', 'LOCAL']) || !integer(value.orderVersion) || !routeFields(value) || !(value.requiredTask === null || typeof value.requiredTask === 'boolean')) malformed()
  return { ...value, item: parseItem(value.item), siteContact: parseContact(value.siteContact) } as unknown as TaskSource
}
function parseTask(value: unknown): TaskSummary {
  if (!exact(value, ['taskId', 'taskNo', 'orderId', 'orderNo', 'orderType', 'orderVersion', 'generation', 'taskKind', 'pickupName', 'deliveryName', 'prerequisiteReady', 'currentAssignmentId', 'policyMode', 'blockingReasons']) || !uuid(value.taskId) || !uuid(value.orderId) || !text(value.taskNo, 100, 1) || !text(value.orderNo, 100, 1) || !member(value.orderType, ['IMPORT', 'EXPORT', 'LOCAL']) || !integer(value.orderVersion) || !integer(value.generation) || !['taskKind', 'pickupName', 'deliveryName'].every((key) => nullable(value[key], (v): v is string => text(v, 200))) || typeof value.prerequisiteReady !== 'boolean' || !nullable(value.currentAssignmentId, uuid) || !policy(value.policyMode) || !blockers(value.blockingReasons)) malformed()
  return value as unknown as TaskSummary
}
function parseDriver(value: unknown): DriverSnapshot { if (!exact(value, ['id', 'driverCode', 'name', 'version']) || !uuid(value.id) || !text(value.driverCode, 40, 1) || !text(value.name, 200, 1) || !integer(value.version)) malformed(); return value as unknown as DriverSnapshot }
function parseVehicle(value: unknown): VehicleSnapshot { if (!exact(value, ['id', 'plateNo', 'vehicleType', 'vehicleTypeCode', 'vehicleTypeLabel', 'version']) || !uuid(value.id) || !uuid(value.vehicleType) || !text(value.plateNo, 40, 1) || !text(value.vehicleTypeCode, 100, 1) || !text(value.vehicleTypeLabel, 200, 1) || !integer(value.version)) malformed(); return value as unknown as VehicleSnapshot }
function parseReservation(value: unknown): Reservation {
  if (!exact(value, ['id', 'kind', 'position', 'resourceId', 'acquiredAt', 'releasedAt', 'releaseReason']) || !uuid(value.id) || !uuid(value.resourceId) || !position(value.position) || value.kind !== (value.position === 'DRIVER' ? 'DRIVER' : 'VEHICLE') || !timestamp(value.acquiredAt) || !nullable(value.releasedAt, timestamp) || !(value.releasedAt === null ? value.releaseReason === null : validReason(value.releaseReason))) malformed()
  return value as unknown as Reservation
}
function parseEvent(value: unknown): DispatchEvent {
  if (!exact(value, ['id', 'action', 'assignmentId', 'previousAssignmentId', 'fromGeneration', 'toGeneration', 'actorId', 'occurredAt', 'reason']) || !uuid(value.id) || !uuid(value.assignmentId) || !nullable(value.previousAssignmentId, uuid) || !uuid(value.actorId) || !member(value.action, ['ASSIGNED', 'REVOKED', 'REASSIGNED']) || !integer(value.fromGeneration) || !integer(value.toGeneration, 1) || value.toGeneration !== value.fromGeneration + 1 || !timestamp(value.occurredAt) || !nullable(value.reason, validReason)) malformed()
  return value as unknown as DispatchEvent
}
function parseAssignment(value: unknown): Assignment {
  if (!exact(value, ['id', 'taskId', 'orderId', 'status', 'version', 'assignedGeneration', 'endedGeneration', 'driver', 'mainVehicle', 'trailer', 'source', 'policyMode', 'assignedBy', 'assignedAt', 'assignmentReason', 'endedBy', 'endedAt', 'endReason', 'previousAssignmentId', 'reservations', 'events']) || !['id', 'taskId', 'orderId', 'assignedBy'].every((key) => uuid(value[key])) || !member(value.status, ['ASSIGNED', 'REVOKED', 'SUPERSEDED']) || value.version !== 0 && value.version !== 1 || !integer(value.assignedGeneration, 1) || !nullable(value.endedGeneration, (v): v is number => integer(v, 1)) || !policy(value.policyMode) || !timestamp(value.assignedAt) || !nullable(value.assignmentReason, validReason) || !nullable(value.endedBy, uuid) || !nullable(value.endedAt, timestamp) || !nullable(value.endReason, validReason) || !nullable(value.previousAssignmentId, uuid) || !Array.isArray(value.reservations) || value.reservations.length < 2 || value.reservations.length > 3 || !Array.isArray(value.events) || value.events.length < 1 || value.events.length > 2) malformed()
  const result = { ...value, driver: parseDriver(value.driver), mainVehicle: parseVehicle(value.mainVehicle), trailer: value.trailer === null ? null : parseVehicle(value.trailer), source: parseSource(value.source), reservations: value.reservations.map(parseReservation), events: value.events.map(parseEvent) } as unknown as Assignment
  if (!sameId(result.source.taskId, result.taskId) || !sameId(result.source.orderId, result.orderId) || result.trailer !== null && sameId(result.trailer.id, result.mainVehicle.id) || result.reservations.length !== (result.trailer === null ? 2 : 3) || new Set(result.reservations.map((r) => r.id.toLowerCase())).size !== result.reservations.length || new Set(result.reservations.map((r) => r.position)).size !== result.reservations.length || new Set(result.events.map((e) => e.id.toLowerCase())).size !== result.events.length) malformed()
  const expected = { DRIVER: result.driver.id, MAIN: result.mainVehicle.id, ...(result.trailer === null ? {} : { TRAILER: result.trailer.id }) }
  if (result.reservations.some((r) => !Object.hasOwn(expected, r.position) || !sameId(r.resourceId, expected[r.position as keyof typeof expected]!)) || result.events.some((e) => !sameId(e.assignmentId, result.id) && !(e.previousAssignmentId !== null && sameId(e.previousAssignmentId, result.id)))) malformed()
  if (result.status === 'ASSIGNED') { if (result.version !== 0 || [result.endedGeneration, result.endedBy, result.endedAt, result.endReason].some((field) => field !== null) || result.reservations.some((r) => r.releasedAt !== null)) malformed() }
  else if (result.version !== 1 || result.endedGeneration === null || result.endedGeneration <= result.assignedGeneration || result.endedBy === null || result.endedAt === null || result.endReason === null || result.reservations.some((r) => r.releasedAt === null)) malformed()
  return result
}
function parsePage<T>(value: unknown, parse: (entry: unknown) => T): TmsPage<T> {
  if (!exact(value, ['content', 'page', 'size', 'totalElements', 'totalPages']) || !Array.isArray(value.content) || !integer(value.page, 0, 100000) || !integer(value.size, 1, 100) || !integer(value.totalElements) || !integer(value.totalPages) || value.content.length > value.size) malformed()
  return { content: value.content.map(parse), page: value.page, size: value.size, totalElements: value.totalElements, totalPages: value.totalPages }
}
function parseDetail(value: unknown, taskId: string): DispatchDetail {
  if (!exact(value, ['task', 'current', 'history'])) malformed()
  const task = parseTask(value.task), current = value.current === null ? null : parseAssignment(value.current), history = parsePage(value.history, parseAssignment)
  if (!sameId(task.taskId, taskId) || (current === null ? task.currentAssignmentId !== null : current.status !== 'ASSIGNED' || task.currentAssignmentId === null || !sameId(task.currentAssignmentId, current.id) || current.assignedGeneration !== task.generation) || [...history.content, ...(current === null ? [] : [current])].some((a) => !sameId(a.taskId, taskId) || !sameId(a.orderId, task.orderId))) malformed()
  return { task, current, history }
}
function parseResource(value: unknown): ResourceCandidate {
  if (!exact(value, ['id', 'kind', 'position', 'code', 'label', 'version', 'administrativelyAvailable', 'occupied', 'occupation', 'policyMode', 'policyApproved', 'blockingReasons']) || !uuid(value.id) || !position(value.position) || value.kind !== (value.position === 'DRIVER' ? 'DRIVER' : 'VEHICLE') || !text(value.code, 40, 1) || !text(value.label, 200, 1) || !integer(value.version) || typeof value.administrativelyAvailable !== 'boolean' || typeof value.occupied !== 'boolean' || !policy(value.policyMode) || typeof value.policyApproved !== 'boolean' || value.policyMode === 'UNCONFIRMED' && value.policyApproved || !blockers(value.blockingReasons)) malformed()
  if (value.occupation !== null && (!exact(value.occupation, ['assignmentId', 'taskId']) || !uuid(value.occupation.assignmentId) || !uuid(value.occupation.taskId)) || value.occupied !== (value.occupation !== null)) malformed()
  return value as unknown as ResourceCandidate
}
function parseMyTask(value: unknown): MyTask {
  if (!exact(value, ['assignmentId', 'taskId', 'taskNo', 'item', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'assignedAt', 'policyMode']) || !uuid(value.assignmentId) || !uuid(value.taskId) || !text(value.taskNo, 100, 1) || !routeFields(value) || !timestamp(value.assignedAt) || !policy(value.policyMode)) malformed()
  return { ...value, item: parseItem(value.item), siteContact: parseContact(value.siteContact) } as unknown as MyTask
}

const statuses: Record<ServerCode, number> = { E0400: 400, E0401: 401, E0403: 403, E0404: 404, E0405: 405, E0415: 415, E0500: 500, E1601: 409, E1602: 409, E1603: 409, E1604: 409, E1605: 409, E1606: 409, E1607: 409, E1608: 409, E1609: 409 }
function parseError(value: unknown, status: number, writing: boolean): TmsApiError {
  if (!exact(value, ['code', 'message', 'data', 'timestamp']) || typeof value.code !== 'string' || !Object.hasOwn(statuses, value.code) || statuses[value.code as ServerCode] !== status || typeof value.message !== 'string' || !companyTimestamp(value.timestamp)) malformed()
  const data = value.data
  if (data !== null && (!record(data) || Object.keys(data).length === 0 || Object.keys(data).some((key) => !['reason', 'actualGeneration', 'fields'].includes(key)) || Object.hasOwn(data, 'reason') && !text(data.reason, Infinity, 1) || Object.hasOwn(data, 'actualGeneration') && !integer(data.actualGeneration) || Object.hasOwn(data, 'fields') && (!record(data.fields) || Object.keys(data.fields).length === 0 || !Object.values(data.fields).every((field) => typeof field === 'string')))) malformed()
  const details = data as { reason?: string; actualGeneration?: number; fields?: Record<string, string> } | null
  if (Object.hasOwn(tmsBusinessReasons, value.code) && details?.reason !== tmsBusinessReasons[value.code as keyof typeof tmsBusinessReasons] || value.code === 'E1601' && details?.actualGeneration === undefined || value.code === 'E0400' && (details?.reason !== 'VALIDATION_ERROR' || details.fields === undefined)) malformed()
  const message = status === 401 ? '登入狀態已失效，請重新登入。' : status >= 500 ? '服務暫時無法使用，請稍後重試。' : value.message
  return new TmsApiError(status, value.code as ServerCode, message, { ...details, uncertain: writing && status >= 500 })
}
function responseError(status: number, writing: boolean, aborted: boolean): TmsApiError { return new TmsApiError(status, aborted ? 'REQUEST_ABORTED' : 'INVALID_RESPONSE', '調度服務回應無法確認，請重新讀取或確認原次操作。', { uncertain: writing }) }
async function transport<T>(path: string, parse: (value: unknown) => T, signal?: AbortSignal, operation?: { serialized: string; key: string }): Promise<TmsSuccess<T> & { replayed?: boolean }> {
  const writing = operation !== undefined
  if (signal?.aborted) throw new TmsApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  let headers: Record<string, string> = {}
  if (writing) {
    try { headers = await getCsrfHeaders(signal) }
    catch (failure) { if (signal?.aborted) throw new TmsApiError(0, 'REQUEST_ABORTED', '連線已中斷。'); throw new TmsApiError(failure instanceof ApiError ? failure.status : 0, failure instanceof ApiError && failure.status === 401 ? 'E0401' : 'NETWORK_ERROR', failure instanceof Error ? failure.message : '無法取得安全驗證，請稍後重試。') }
  }
  if (signal?.aborted) throw new TmsApiError(0, 'REQUEST_ABORTED', '連線已中斷。')
  const controller = new AbortController(), abort = () => controller.abort(); signal?.addEventListener('abort', abort, { once: true }); const timeout = window.setTimeout(abort, 10000)
  try {
    const response = await fetch(`/api/v1/tms${path}`, { method: writing ? 'POST' : 'GET', credentials: 'same-origin', signal: controller.signal, headers: { Accept: 'application/json', ...(operation ? { 'Content-Type': 'application/json', ...headers, 'Idempotency-Key': operation.key } : {}) }, ...(operation ? { body: operation.serialized } : {}) })
    let payload: unknown
    try { payload = readTomJson(new TextDecoder('utf-8', { fatal: true }).decode(await response.arrayBuffer())) } catch { throw responseError(response.status, writing, controller.signal.aborted) }
    if (!response.ok) {
      let failure: TmsApiError; try { failure = parseError(payload, response.status, writing) } catch { throw responseError(response.status, writing, controller.signal.aborted) }
      if (failure.status === 403 && failure.reason === 'CSRF_INVALID') {
        try { await refreshCsrf(signal) }
        catch (refreshFailure) {
          // A later authorization refusal must clear protected UI data, not be hidden as CSRF.
          if (refreshFailure instanceof ApiError && [401, 403].includes(refreshFailure.status)) throw new TmsApiError(refreshFailure.status, refreshFailure.status === 401 ? 'E0401' : 'E0403', '登入或存取狀態已失效，請重新確認。', { reason: refreshFailure.status === 403 ? 'FORBIDDEN' : null, uncertain: writing })
          // Other refresh failures preserve the original CSRF result and original command identity.
        }
      }
      throw failure
    }
    try {
      if (response.status !== 200 || !exact(payload, ['code', 'message', 'data', 'timestamp']) || payload.code !== '0000' || typeof payload.message !== 'string' || !companyTimestamp(payload.timestamp)) malformed()
      const data = parse(payload.data), replayed = response.headers.get('Idempotency-Replayed')
      if (writing && replayed !== 'true' && replayed !== 'false') malformed()
      return { code: '0000', message: payload.message, data, timestamp: payload.timestamp, ...(writing ? { replayed: replayed === 'true' } : {}) }
    } catch { throw responseError(response.status, writing, controller.signal.aborted) }
  } catch (failure) { if (failure instanceof TmsApiError) throw failure; throw new TmsApiError(0, controller.signal.aborted ? 'REQUEST_ABORTED' : 'NETWORK_ERROR', controller.signal.aborted ? '連線逾時或中斷，請確認原次操作。' : '無法確認服務回應，請檢查連線後再操作。', { uncertain: writing }) }
  finally { window.clearTimeout(timeout); signal?.removeEventListener('abort', abort) }
}
function queryValues(query: unknown, allowed: readonly string[]): asserts query is Record<string, unknown> { if (!record(query) || Object.keys(query).some((key) => !allowed.includes(key))) input('不支援此調度查詢條件。') }
function queryPage(page: unknown, size: unknown): URLSearchParams { if (!integer(page, 0, 100000) || !integer(size, 1, 100)) input('頁碼或每頁筆數不正確。'); return new URLSearchParams({ page: String(page), size: String(size) }) }
function search(params: URLSearchParams, q: unknown): void { if (q === undefined) return; if (typeof q !== 'string' || !unicode(q)) input('搜尋文字格式不正確。'); const value = normalized(q); if ([...value].length > 100 || /\p{Cc}/u.test(value)) input('搜尋最多 100 字，且不可包含控制字元。'); if (q) params.set('q', q) }
function id(value: unknown, field: string): string { if (!uuid(value)) input('請提供完整 UUID。', { [field]: '識別格式不正確。' }); return value.toLowerCase() }
export async function getTmsTasks(page: number, query: TaskQuery = {}, signal?: AbortSignal): Promise<TmsPage<TaskSummary>> {
  queryValues(query, ['size', 'state', 'orderId', 'q']); const params = queryPage(page, query.size ?? 20); search(params, query.q)
  if (query.state !== undefined) { if (!member(query.state, ['UNASSIGNED', 'ASSIGNED', 'ALL'])) input('派工狀態篩選不正確。'); params.set('state', query.state) }
  if (query.orderId !== undefined) params.set('orderId', id(query.orderId, 'orderId'))
  return (await transport(`/tasks?${params}`, (value) => parsePage(value, parseTask), signal)).data
}
export async function getTaskDispatch(taskId: string, query: HistoryQuery = {}, signal?: AbortSignal): Promise<DispatchDetail> {
  const target = id(taskId, 'taskId'); queryValues(query, ['historyPage', 'historySize']); const page = query.historyPage ?? 0, size = query.historySize ?? 20; queryPage(page, size)
  return (await transport(`/tasks/${target}/dispatch?${new URLSearchParams({ historyPage: String(page), historySize: String(size) })}`, (value) => parseDetail(value, target), signal)).data
}
export async function getTmsResources(query: ResourceQuery, signal?: AbortSignal): Promise<TmsPage<ResourceCandidate>> {
  queryValues(query, ['taskId', 'position', 'assignmentId', 'page', 'size', 'q']); const task = id(query.taskId, 'taskId'); if (!position(query.position)) input('請選擇 DRIVER、MAIN 或 TRAILER。'); const selectedPosition = query.position
  const params = queryPage(query.page ?? 0, query.size ?? 20); params.set('taskId', task); params.set('position', selectedPosition); if (query.assignmentId !== undefined) params.set('assignmentId', id(query.assignmentId, 'assignmentId')); search(params, query.q)
  return (await transport(`/resources?${params}`, (value) => parsePage(value, (entry) => { const resource = parseResource(entry); if (resource.position !== selectedPosition) malformed(); return resource }), signal)).data
}
export async function getMyTmsTasks(page: number, query: MyTaskQuery = {}, signal?: AbortSignal): Promise<TmsPage<MyTask>> { queryValues(query, ['size']); const params = queryPage(page, query.size ?? 20); return (await transport(`/my-tasks?${params}`, (value) => parsePage(value, parseMyTask), signal)).data }
type Action = 'assign' | 'revoke' | 'reassign'
function commandBody(body: AssignRequest | RevokeRequest | ReassignRequest, action: Action): Record<string, unknown> {
  const keys = action === 'assign' ? ['expectedGeneration', 'driverId', 'mainVehicleId', 'trailerId'] : action === 'revoke' ? ['expectedGeneration', 'reason'] : ['expectedGeneration', 'driverId', 'mainVehicleId', 'trailerId', 'reason']
  if (!exact(body, keys) || ![Object.prototype, null].includes(Object.getPrototypeOf(body)) || Reflect.ownKeys(body).length !== keys.length) input('請依操作提供完整且明確的欄位。')
  const frozen = Object.fromEntries(Object.keys(body).map((key) => [key, body[key]])); if (!integer(frozen.expectedGeneration)) input('派工版本須為安全非負整數。', { expectedGeneration: '版本不正確。' })
  if (action !== 'revoke') { id(frozen.driverId, 'driverId'); id(frozen.mainVehicleId, 'mainVehicleId'); if (frozen.trailerId !== null) id(frozen.trailerId, 'trailerId'); if (frozen.trailerId !== null && sameId(frozen.mainVehicleId as string, frozen.trailerId as string)) input('主車與拖板不可使用同一車輛。', { trailerId: '請選擇另一車輛或明確不使用拖板。' }) }
  if (action !== 'assign' && (typeof frozen.reason !== 'string' || !unicode(frozen.reason) || !validReason(normalized(frozen.reason)))) input('請提供正規化後 1–500 字的撤派／改派原因。', { reason: '原因不可空白或包含其他控制字元。' })
  return Object.freeze(frozen)
}
function parseCommand(value: unknown, target: string, taskId: string, body: Record<string, unknown>, action: Action): CommandResult {
  if (!exact(value, ['taskId', 'generation', 'assignment']) || !uuid(value.taskId) || !integer(value.generation, 1) || value.generation !== (body.expectedGeneration as number) + 1 || !sameId(value.taskId, taskId)) malformed()
  const assignment = parseAssignment(value.assignment); if (!sameId(assignment.taskId, taskId)) malformed()
  if (action === 'revoke') { if (!sameId(assignment.id, target) || assignment.status !== 'REVOKED' || assignment.endedGeneration !== value.generation) malformed() }
  else {
    if (assignment.status !== 'ASSIGNED' || assignment.assignedGeneration !== value.generation || !sameId(assignment.driver.id, body.driverId as string) || !sameId(assignment.mainVehicle.id, body.mainVehicleId as string) || (body.trailerId === null ? assignment.trailer !== null : assignment.trailer === null || !sameId(assignment.trailer.id, body.trailerId as string))) malformed()
    if (action === 'assign' ? assignment.previousAssignmentId !== null : sameId(assignment.id, target) || assignment.previousAssignmentId === null || !sameId(assignment.previousAssignmentId, target)) malformed()
  }
  const eventAction = action === 'assign' ? 'ASSIGNED' : action === 'revoke' ? 'REVOKED' : 'REASSIGNED'
  const expectedReason = action === 'assign' ? null : normalized(body.reason as string)
  if ((action === 'revoke' ? assignment.endReason : assignment.assignmentReason) !== expectedReason || !assignment.events.some((event) => event.action === eventAction && sameId(event.assignmentId, assignment.id) && event.fromGeneration === body.expectedGeneration && event.toGeneration === value.generation && event.reason === expectedReason && (action === 'reassign' ? event.previousAssignmentId !== null && sameId(event.previousAssignmentId, target) : event.previousAssignmentId === null))) malformed()
  return { taskId: value.taskId, generation: value.generation, assignment }
}
async function command(targetId: string, body: AssignRequest | RevokeRequest | ReassignRequest, key: string, action: Action, context: DispatchCommandContext | undefined, signal?: AbortSignal): Promise<TmsWriteResult> {
  const target = id(targetId, action === 'assign' ? 'taskId' : 'assignmentId'), taskId = action === 'assign' ? target : context && exact(context, ['taskId']) ? id(context.taskId, 'taskId') : input('請明確提供此派工所屬任務。')
  const snapshot = commandBody(body, action); if (typeof key !== 'string' || !full(/^[A-Za-z0-9._:-]{1,128}$/, key)) input('請提供明確有效的重送識別。')
  const serialized = JSON.stringify(snapshot), originalKey = key, path = action === 'assign' ? `/tasks/${target}/dispatch` : `/dispatches/${target}/${action}`
  return await transport(path, (value) => parseCommand(value, target, taskId, snapshot, action), signal, { serialized, key: originalKey }) as TmsWriteResult
}
export function assignTask(taskId: string, body: AssignRequest, key: string, signal?: AbortSignal): Promise<TmsWriteResult> { return command(taskId, body, key, 'assign', undefined, signal) }
export function revokeDispatch(assignmentId: string, body: RevokeRequest, key: string, context: DispatchCommandContext, signal?: AbortSignal): Promise<TmsWriteResult> { return command(assignmentId, body, key, 'revoke', context, signal) }
export function reassignDispatch(assignmentId: string, body: ReassignRequest, key: string, context: DispatchCommandContext, signal?: AbortSignal): Promise<TmsWriteResult> { return command(assignmentId, body, key, 'reassign', context, signal) }
