import { ApiError, request, write } from './api'
import type { PageData } from './admApi'

export const employmentStatuses = ['ACTIVE', 'INACTIVE', 'LEFT'] as const
export const dutyStatuses = ['ON_DUTY', 'OFF_DUTY'] as const
export type EmploymentStatus = typeof employmentStatuses[number]
export type DutyStatus = typeof dutyStatuses[number]
export interface DriverSelf {
  id: string; driverCode: string; name: string; phone: string | null; email: string | null
  employmentStatus: EmploymentStatus; dutyStatus: DutyStatus; licenseType: string | null
  licenseTypeCode: string | null; licenseTypeLabel: string | null; licenseTypeEnabled: boolean; licenseDueDate: string | null
}
export interface DriverSummary extends DriverSelf {
  accountId: string | null; accountUsername: string | null; accountDisplayName: string | null; accountBindingValid: boolean; version: number
}
export interface FullDriver extends DriverSummary { licenseReference: string | null; createdAt: string; createdBy: string; updatedAt: string; updatedBy: string }
export type Driver = FullDriver
export type DriverOption = Pick<DriverSelf, 'id' | 'driverCode' | 'name' | 'licenseType' | 'licenseTypeCode' | 'licenseTypeLabel' | 'licenseTypeEnabled' | 'licenseDueDate'> & { version: number }
export interface LicenseType { id: string; code: string; label: string }
export interface AccountOption { id: string; username: string; displayName: string; bound: boolean }
export type DriverLicenseType = LicenseType
export type DriverAccountOption = AccountOption
export interface DriverAccountQuery { q?: string; size?: number }
export interface DriverOptionQuery extends DriverAccountQuery { licenseType?: string }
export interface DriverQuery extends DriverOptionQuery { employmentStatus?: EmploymentStatus | 'all'; dutyStatus?: DutyStatus | 'all' }
export type DriverWrite = Pick<FullDriver, 'name' | 'phone' | 'email' | 'accountId' | 'employmentStatus' | 'dutyStatus' | 'licenseType' | 'licenseReference' | 'licenseDueDate'>
export type DriverCreate = Pick<FullDriver, 'driverCode' | 'name'> & Partial<Omit<DriverWrite, 'name'>>
export type DriverPatch = Partial<DriverWrite> & { version: number }

const identityKeys = ['id', 'driverCode', 'name'] as const
const licenseKeys = ['licenseType', 'licenseTypeCode', 'licenseTypeLabel', 'licenseTypeEnabled', 'licenseDueDate'] as const
const selfKeys = [...identityKeys, 'phone', 'email', 'employmentStatus', 'dutyStatus', ...licenseKeys]
const summaryKeys = [...selfKeys, 'accountId', 'accountUsername', 'accountDisplayName', 'accountBindingValid', 'version']
const fullKeys = [...summaryKeys, 'licenseReference', 'createdAt', 'createdBy', 'updatedAt', 'updatedBy']
const optionKeys = [...identityKeys, ...licenseKeys, 'version']
function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function exact(value: unknown, keys: readonly string[]): value is Record<string, unknown> { return record(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key)) }
function invalid(): never { throw new ApiError(0, '司機資料回應格式不正確，請重新載入。') }
function invalidInput(message: string): never { throw new ApiError(400, message) }
function full(pattern: RegExp, value: string): boolean { return pattern.exec(value)?.[0] === value }
function uuid(value: unknown): value is string { return typeof value === 'string' && full(/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i, value) }
function safeInteger(value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum }
function unicode(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false }
    else if (code >= 0xdc00 && code <= 0xdfff) return false
  }
  return true
}
function text(value: unknown, maximum: number): value is string { return typeof value === 'string' && unicode(value) && [...value].length >= 1 && [...value].length <= maximum && /[^\p{White_Space}]/u.test(value) && !/\p{Cc}/u.test(value) }
function nullable<T>(value: unknown, parse: (item: unknown) => item is T): value is T | null { return value === null || parse(value) }
function code(value: unknown): value is string { return typeof value === 'string' && full(/^[A-Z][A-Z0-9_]{1,63}$/, value) }
function username(value: unknown): value is string { return typeof value === 'string' && full(/^[a-z0-9][a-z0-9._-]{2,63}$/, value) }
function date(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1).map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = /^(\d{4}-\d{2}-\d{2})[Tt]([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value)
  return Boolean(match && match[0] === value && date(match[1]))
}
function employment(value: unknown): value is EmploymentStatus { return employmentStatuses.includes(value as EmploymentStatus) }
function duty(value: unknown): value is DutyStatus { return dutyStatuses.includes(value as DutyStatus) }
function validIdentity(value: Record<string, unknown>): boolean { return uuid(value.id) && text(value.driverCode, 40) && text(value.name, 200) }
function validLicense(value: Record<string, unknown>): boolean {
  if (!nullable(value.licenseDueDate, date)) return false
  return value.licenseType === null
    ? value.licenseTypeCode === null && value.licenseTypeLabel === null && value.licenseTypeEnabled === false
    : uuid(value.licenseType) && code(value.licenseTypeCode) && text(value.licenseTypeLabel, 80) && typeof value.licenseTypeEnabled === 'boolean'
}
function validSelf(value: Record<string, unknown>): boolean {
  return validIdentity(value) && validLicense(value) && nullable(value.phone, (item): item is string => text(item, 50)) && nullable(value.email, (item): item is string => text(item, 254)) && employment(value.employmentStatus) && duty(value.dutyStatus)
}
function validSummary(value: Record<string, unknown>): boolean {
  const binding = value.accountId === null
    ? value.accountUsername === null && value.accountDisplayName === null && value.accountBindingValid === false
    : uuid(value.accountId) && username(value.accountUsername) && text(value.accountDisplayName, 80) && typeof value.accountBindingValid === 'boolean'
  return validSelf(value) && safeInteger(value.version) && binding
}
function parseSelf(value: unknown): DriverSelf { if (!exact(value, selfKeys) || !validSelf(value)) invalid(); return value as unknown as DriverSelf }
function parseSummary(value: unknown): DriverSummary { if (!exact(value, summaryKeys) || !validSummary(value)) invalid(); return value as unknown as DriverSummary }
function parseDriver(value: unknown): FullDriver {
  if (!exact(value, fullKeys) || !validSummary(value) || !nullable(value.licenseReference, (item): item is string => text(item, 100)) || !timestamp(value.createdAt) || !timestamp(value.updatedAt) || !uuid(value.createdBy) || !uuid(value.updatedBy)) invalid()
  return value as unknown as FullDriver
}
function parseOption(value: unknown): DriverOption { if (!exact(value, optionKeys) || !validIdentity(value) || !validLicense(value) || !safeInteger(value.version)) invalid(); return value as unknown as DriverOption }
function parseType(value: unknown): LicenseType { if (!exact(value, ['id', 'code', 'label']) || !uuid(value.id) || !code(value.code) || !text(value.label, 80)) invalid(); return value as unknown as LicenseType }
function parseAccount(value: unknown): AccountOption { if (!exact(value, ['id', 'username', 'displayName', 'bound']) || !uuid(value.id) || !username(value.username) || !text(value.displayName, 80) || typeof value.bound !== 'boolean') invalid(); return value as unknown as AccountOption }
function parsePage<T>(value: unknown, parse: (item: unknown) => T): PageData<T> {
  if (!exact(value, ['content', 'page', 'size', 'totalElements', 'totalPages']) || !Array.isArray(value.content) || !safeInteger(value.page, 0, 100000) || !safeInteger(value.size, 1, 100) || !safeInteger(value.totalElements) || !safeInteger(value.totalPages) || value.content.length > value.size) invalid()
  return { content: value.content.map(parse), page: value.page, size: value.size, totalElements: value.totalElements, totalPages: value.totalPages }
}
function stripBoundary(value: string): string {
  const boundary = (character: string) => character !== '\u0085' && /\p{White_Space}/u.test(character) || character === '\uFEFF' || character.charCodeAt(0) >= 0x1c && character.charCodeAt(0) <= 0x1f
  let start = 0, end = value.length
  while (start < end && boundary(value[start])) start++
  while (end > start && boundary(value[end - 1])) end--
  return value.slice(start, end)
}
function queryString(page: number, query: DriverQuery | DriverOptionQuery | DriverAccountQuery, kind: 'list' | 'option' | 'account'): string {
  const allowed = kind === 'list' ? ['q', 'size', 'licenseType', 'employmentStatus', 'dutyStatus'] : kind === 'option' ? ['q', 'size', 'licenseType'] : ['q', 'size']
  if (Object.keys(query).some((key) => !allowed.includes(key))) invalidInput('不支援此司機查詢條件。')
  if (!safeInteger(page, 0, 100000) || !safeInteger(query.size ?? 20, 1, 100)) invalidInput('司機頁碼或每頁筆數不正確。')
  if (query.q !== undefined) {
    if (typeof query.q !== 'string' || !unicode(query.q)) invalidInput('司機搜尋文字格式不正確。')
    const normalized = stripBoundary(query.q.normalize('NFC'))
    if (/\p{Cc}/u.test(normalized) || [...normalized].length > 100) invalidInput('司機搜尋最多 100 字，且不可包含控制字元。')
  }
  const params = new URLSearchParams({ page: String(page), size: String(query.size ?? 20) })
  if (query.q) params.set('q', query.q)
  if ('licenseType' in query && query.licenseType !== undefined) { if (!uuid(query.licenseType)) invalidInput('駕照類別識別格式不正確。'); params.set('licenseType', query.licenseType) }
  if ('employmentStatus' in query && query.employmentStatus !== undefined && query.employmentStatus !== 'all') { if (!employment(query.employmentStatus)) invalidInput('在職狀態不正確。'); params.set('employmentStatus', query.employmentStatus) }
  if ('dutyStatus' in query && query.dutyStatus !== undefined && query.dutyStatus !== 'all') { if (!duty(query.dutyStatus)) invalidInput('出勤狀態不正確。'); params.set('dutyStatus', query.dutyStatus) }
  return params.toString()
}
function pathFor(id: string): string { if (!uuid(id)) invalidInput('司機識別格式不正確。'); return `/fms/drivers/${encodeURIComponent(id)}` }
async function driverWrite(path: string, method: string, data: DriverCreate | DriverPatch): Promise<FullDriver> {
  try { return parseDriver(await write(path, method, data)) }
  catch (failure) {
    // A 2xx response with an unreadable envelope cannot establish whether the write committed.
    if (failure instanceof ApiError && failure.status >= 200 && failure.status < 300) throw new ApiError(0, '無法確認司機儲存結果，請先重新讀取資料再操作。')
    throw failure
  }
}
export async function getDrivers(page: number, query: DriverQuery = {}, signal?: AbortSignal): Promise<PageData<DriverSummary>> { return parsePage(await request(`/fms/drivers?${queryString(page, query, 'list')}`, 'GET', undefined, signal), parseSummary) }
export async function getDriver(id: string, signal?: AbortSignal): Promise<FullDriver> { return parseDriver(await request(pathFor(id), 'GET', undefined, signal)) }
export async function createDriver(data: DriverCreate): Promise<FullDriver> { return driverWrite('/fms/drivers', 'POST', data) }
export async function updateDriver(id: string, data: DriverPatch): Promise<FullDriver> { return driverWrite(pathFor(id), 'PATCH', data) }
export async function getDriverOptions(page: number, query: DriverOptionQuery = {}, signal?: AbortSignal): Promise<PageData<DriverOption>> { return parsePage(await request(`/fms/driver-options?${queryString(page, query, 'option')}`, 'GET', undefined, signal), parseOption) }
export async function getDriverLicenseTypes(signal?: AbortSignal): Promise<LicenseType[]> { const data = await request('/fms/driver-license-types', 'GET', undefined, signal); if (!Array.isArray(data)) invalid(); return data.map(parseType) }
export async function getDriverAccountOptions(page: number, query: DriverAccountQuery = {}, signal?: AbortSignal): Promise<PageData<AccountOption>> { return parsePage(await request(`/fms/driver-account-options?${queryString(page, query, 'account')}`, 'GET', undefined, signal), parseAccount) }
export async function getDriverMe(signal?: AbortSignal): Promise<DriverSelf> { return parseSelf(await request('/fms/drivers/me', 'GET', undefined, signal)) }
