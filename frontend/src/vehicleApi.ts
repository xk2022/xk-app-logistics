import { ApiError, request, write } from './api'
import type { PageData } from './admApi'

export const administrativeStatuses = ['AVAILABLE', 'MAINTENANCE', 'INACTIVE'] as const
export type AdministrativeStatus = typeof administrativeStatuses[number]
export type VehicleStatus = AdministrativeStatus
export interface VehicleTypeOption { id: string; code: string; label: string }
export interface VehicleOption {
  id: string; plateNo: string; vehicleType: string; vehicleTypeCode: string; vehicleTypeLabel: string
  vehicleTypeEnabled: boolean; capacityTon: string | null; version: number
}
export interface Vehicle extends VehicleOption {
  registrationNo: string | null; registrationDate: string | null; inspectionDueDate: string | null
  administrativeStatus: AdministrativeStatus; createdAt: string; createdBy: string; updatedAt: string; updatedBy: string
}
export interface VehicleOptionQuery { q?: string; vehicleType?: string; size?: number }
export interface VehicleQuery extends VehicleOptionQuery { administrativeStatus?: AdministrativeStatus | 'all' }
export type VehicleWrite = Pick<Vehicle, 'plateNo' | 'vehicleType' | 'capacityTon' | 'registrationNo' | 'registrationDate' | 'inspectionDueDate' | 'administrativeStatus'>
export type VehicleCreate = Pick<VehicleWrite, 'plateNo' | 'vehicleType'> & Partial<Omit<VehicleWrite, 'plateNo' | 'vehicleType'>>
export type VehiclePatch = Partial<VehicleWrite> & { version: number }

function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function invalid(): never { throw new ApiError(0, '車輛資料回應格式不正確，請重新載入。') }
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
function text(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && unicode(value) && [...value].length >= 1 && [...value].length <= maximum && /[^\p{White_Space}]/u.test(value) && !/\p{Cc}/u.test(value)
}
function stripBoundary(value: string): string {
  const boundary = (character: string) => character !== '\u0085' && /\p{White_Space}/u.test(character) || character === '\uFEFF' || character.charCodeAt(0) >= 0x1c && character.charCodeAt(0) <= 0x1f
  let start = 0, end = value.length
  while (start < end && boundary(value[start])) start++
  while (end > start && boundary(value[end - 1])) end--
  return value.slice(start, end)
}
function nullable<T>(value: unknown, parse: (value: unknown) => value is T): value is T | null { return value === null || parse(value) }
function code(value: unknown): value is string { return typeof value === 'string' && full(/^[A-Z][A-Z0-9_]{1,63}$/, value) }
function decimal(value: unknown): value is string { return typeof value === 'string' && value.length <= 32 && full(/^(0|[1-9][0-9]*)(\.[0-9]+)?$/, value) }
export function validVehicleDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1).map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
function date(value: unknown): value is string { return typeof value === 'string' && validVehicleDate(value) }
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = /^(\d{4}-\d{2}-\d{2})[Tt]([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value)
  return Boolean(match && match[0] === value && validVehicleDate(match[1]))
}
function status(value: unknown): value is AdministrativeStatus { return administrativeStatuses.includes(value as AdministrativeStatus) }
function parseOption(value: unknown): VehicleOption {
  if (!record(value) || !uuid(value.id) || !text(value.plateNo, 40) || !uuid(value.vehicleType) || !code(value.vehicleTypeCode) || !text(value.vehicleTypeLabel, 80)
    || typeof value.vehicleTypeEnabled !== 'boolean' || !nullable(value.capacityTon, decimal) || !safeInteger(value.version)) invalid()
  return value as unknown as VehicleOption
}
function parseVehicle(value: unknown): Vehicle {
  parseOption(value)
  if (!record(value) || !nullable(value.registrationNo, (item): item is string => text(item, 100)) || !nullable(value.registrationDate, date) || !nullable(value.inspectionDueDate, date)
    || !status(value.administrativeStatus) || !timestamp(value.createdAt) || !timestamp(value.updatedAt) || !uuid(value.createdBy) || !uuid(value.updatedBy)) invalid()
  return value as unknown as Vehicle
}
function parseType(value: unknown): VehicleTypeOption {
  if (!record(value) || !uuid(value.id) || !code(value.code) || !text(value.label, 80)) invalid()
  return value as unknown as VehicleTypeOption
}
function parsePage<T>(value: unknown, parse: (item: unknown) => T): PageData<T> {
  if (!record(value) || !Array.isArray(value.content) || !safeInteger(value.page, 0, 100000) || !safeInteger(value.size, 1, 100)
    || !safeInteger(value.totalElements) || !safeInteger(value.totalPages) || value.content.length > value.size) invalid()
  return { content: value.content.map(parse), page: value.page, size: value.size, totalElements: value.totalElements, totalPages: value.totalPages }
}
function queryString(page: number, query: VehicleQuery | VehicleOptionQuery, options: boolean): string {
  if (!safeInteger(page, 0, 100000) || !safeInteger(query.size ?? 20, 1, 100)) invalidInput('車輛頁碼或每頁筆數不正確。')
  if (Object.keys(query).some((field) => !(options ? ['q', 'vehicleType', 'size'] : ['q', 'vehicleType', 'size', 'administrativeStatus']).includes(field))) invalidInput('不支援此車輛查詢條件。')
  if (query.q !== undefined) {
    if (typeof query.q !== 'string' || !unicode(query.q)) invalidInput('車輛搜尋文字格式不正確。')
    const normalized = stripBoundary(query.q.normalize('NFC'))
    if (/\p{Cc}/u.test(normalized) || [...normalized].length > 100) invalidInput('車輛搜尋最多 100 字，且不可包含控制字元。')
  }
  if (query.vehicleType !== undefined && !uuid(query.vehicleType)) invalidInput('車種識別格式不正確。')
  const params = new URLSearchParams({ page: String(page), size: String(query.size ?? 20) })
  if (query.q) params.set('q', query.q)
  if (query.vehicleType !== undefined) params.set('vehicleType', query.vehicleType)
  if ('administrativeStatus' in query && query.administrativeStatus !== undefined && query.administrativeStatus !== 'all') {
    if (!status(query.administrativeStatus)) invalidInput('車輛管理狀態不正確。')
    params.set('administrativeStatus', query.administrativeStatus)
  }
  return params.toString()
}
const pathFor = (id: string) => { if (!uuid(id)) invalidInput('車輛識別格式不正確。'); return `/fms/vehicles/${encodeURIComponent(id)}` }
export async function getVehicles(page: number, query: VehicleQuery = {}, signal?: AbortSignal): Promise<PageData<Vehicle>> {
  return parsePage(await request(`/fms/vehicles?${queryString(page, query, false)}`, 'GET', undefined, signal), parseVehicle)
}
export async function getVehicle(id: string, signal?: AbortSignal): Promise<Vehicle> { return parseVehicle(await request(pathFor(id), 'GET', undefined, signal)) }
export async function createVehicle(data: VehicleCreate): Promise<Vehicle> { return parseVehicle(await write('/fms/vehicles', 'POST', data)) }
export async function updateVehicle(id: string, data: VehiclePatch): Promise<Vehicle> { return parseVehicle(await write(pathFor(id), 'PATCH', data)) }
export async function getVehicleOptions(page: number, query: VehicleOptionQuery = {}, signal?: AbortSignal): Promise<PageData<VehicleOption>> {
  return parsePage(await request(`/fms/vehicle-options?${queryString(page, query, true)}`, 'GET', undefined, signal), parseOption)
}
export async function getVehicleTypes(signal?: AbortSignal): Promise<VehicleTypeOption[]> {
  const data = await request('/fms/vehicle-types', 'GET', undefined, signal)
  if (!Array.isArray(data)) invalid()
  return data.map(parseType)
}
