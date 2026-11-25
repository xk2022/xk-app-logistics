import { ApiError, request, write } from './api'

export interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface Category { id: string; code: string; name: string; enabled: boolean; sortOrder: number; readOnly: boolean; version: number }
export interface Item { id: string; categoryId: string; code: string; label: string; enabled: boolean; sortOrder: number; readOnly: boolean; version: number }
export type ValueType = 'TEXT' | 'NUMBER' | 'BOOLEAN'
export interface Parameter { id: string; key: string; name: string; valueType: ValueType; valueText: string; description: string; enabled: boolean; version: number }
export interface Definition { key: string; name: string; valueType: ValueType; description: string; minLength: number | null; maxLength: number | null; allowedValues: string[]; minValue: string | null; maxValue: string | null; decimalScale: number | null }
export interface ChangeLog { id: string; targetType: string; targetId: string; action: string; actorId: string; actorUsername: string; occurredAt: string; before: unknown; after: unknown }
export type CategoryWrite = Pick<Category, 'name' | 'enabled' | 'sortOrder'>
export type ItemWrite = Pick<Item, 'label' | 'enabled' | 'sortOrder'>
export type ParameterWrite = Pick<Parameter, 'name' | 'valueText' | 'description' | 'enabled'>

function isRecord(value: unknown): value is Record<string, unknown> { return Boolean(value) && typeof value === 'object' && !Array.isArray(value) }
function invalid(): never { throw new ApiError(0, '管理資料回應格式不正確，請重新載入。') }
function base(value: unknown): value is Record<string, unknown> {
  return isRecord(value) && typeof value.id === 'string' && typeof value.enabled === 'boolean' && Number.isInteger(value.version) && (value.version as number) >= 0
}
function parseCategory(value: unknown): Category {
  if (!base(value) || typeof value.code !== 'string' || typeof value.name !== 'string' || !Number.isInteger(value.sortOrder) || typeof value.readOnly !== 'boolean') invalid()
  return value as unknown as Category
}
function parseItem(value: unknown): Item {
  if (!base(value) || typeof value.categoryId !== 'string' || typeof value.code !== 'string' || typeof value.label !== 'string' || !Number.isInteger(value.sortOrder) || typeof value.readOnly !== 'boolean') invalid()
  return value as unknown as Item
}
function isType(value: unknown): value is ValueType { return value === 'TEXT' || value === 'NUMBER' || value === 'BOOLEAN' }
function parseParameter(value: unknown): Parameter {
  if (!base(value) || typeof value.key !== 'string' || typeof value.name !== 'string' || !isType(value.valueType) || typeof value.valueText !== 'string' || typeof value.description !== 'string') invalid()
  return value as unknown as Parameter
}
function parseDefinition(value: unknown): Definition {
  if (!isRecord(value) || typeof value.key !== 'string' || typeof value.name !== 'string' || !isType(value.valueType) || typeof value.description !== 'string'
    || !Array.isArray(value.allowedValues) || !value.allowedValues.every((item) => typeof item === 'string')
    || !['minLength', 'maxLength', 'decimalScale'].every((key) => value[key] === null || Number.isInteger(value[key]) && (value[key] as number) >= 0)
    || !['minValue', 'maxValue'].every((key) => value[key] === null || typeof value[key] === 'string' && /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value[key] as string))) invalid()
  return value as unknown as Definition
}
function parseLog(value: unknown): ChangeLog {
  if (!isRecord(value) || !['id', 'targetType', 'targetId', 'action', 'actorId', 'actorUsername', 'occurredAt'].every((key) => typeof value[key] === 'string') || Number.isNaN(Date.parse(value.occurredAt as string)) || !('before' in value) || !('after' in value)) invalid()
  return value as unknown as ChangeLog
}
async function pageRequest<T>(path: string, parser: (value: unknown) => T, page: number, signal?: AbortSignal): Promise<PageData<T>> {
  const query = new URLSearchParams({ page: String(page), size: '20' })
  const data = await request(`${path}?${query}`, 'GET', undefined, signal)
  if (!isRecord(data) || !Array.isArray(data.content) || !['page', 'size', 'totalElements', 'totalPages'].every((key) => Number.isInteger(data[key]) && (data[key] as number) >= 0) || data.size === 0) invalid()
  return { content: data.content.map(parser), page: data.page as number, size: data.size as number, totalElements: data.totalElements as number, totalPages: data.totalPages as number }
}
export const getCategories = (page: number, signal?: AbortSignal) => pageRequest('/adm/categories', parseCategory, page, signal)
export const getItems = (categoryId: string, page: number, signal?: AbortSignal) => pageRequest(`/adm/categories/${encodeURIComponent(categoryId)}/items`, parseItem, page, signal)
export const getParameters = (page: number, signal?: AbortSignal) => pageRequest('/adm/parameters', parseParameter, page, signal)
export const getChangeLogs = (page: number, signal?: AbortSignal) => pageRequest('/adm/change-logs', parseLog, page, signal)
export async function getDefinitions(signal?: AbortSignal): Promise<Definition[]> {
  const data = await request('/adm/parameter-definitions', 'GET', undefined, signal)
  if (!Array.isArray(data)) invalid()
  return data.map(parseDefinition)
}
export async function getOptions(code: string, signal?: AbortSignal): Promise<Item[]> {
  const data = await request(`/adm/options/${encodeURIComponent(code)}`, 'GET', undefined, signal)
  if (!Array.isArray(data)) invalid()
  return data.map(parseItem)
}
export const createCategory = async (data: CategoryWrite & { code: string }) => parseCategory(await write('/adm/categories', 'POST', data))
export const updateCategory = async (id: string, data: CategoryWrite & { version: number }) => parseCategory(await write(`/adm/categories/${encodeURIComponent(id)}`, 'PATCH', data))
export const createItem = async (categoryId: string, data: ItemWrite & { code: string }) => parseItem(await write(`/adm/categories/${encodeURIComponent(categoryId)}/items`, 'POST', data))
export const updateItem = async (id: string, data: ItemWrite & { version: number }) => parseItem(await write(`/adm/items/${encodeURIComponent(id)}`, 'PATCH', data))
export const createParameter = async (data: ParameterWrite & { key: string; valueType: ValueType }) => parseParameter(await write('/adm/parameters', 'POST', data))
export const updateParameter = async (id: string, data: ParameterWrite & { version: number }) => parseParameter(await write(`/adm/parameters/${encodeURIComponent(id)}`, 'PATCH', data))

export function validateParameterValue(value: string, definition: Definition): string | null {
  if ([...value.normalize('NFC')].length > 2000) return '參數值最多為 2000 字元。'
  if (definition.valueType === 'BOOLEAN') return value === 'true' || value === 'false' ? null : '請選擇 true 或 false。'
  if (definition.valueType === 'TEXT') {
    const length = [...value.normalize('NFC')].length
    if (definition.minLength !== null && length < definition.minLength) return `文字至少須為 ${definition.minLength} 字元。`
    if (definition.maxLength !== null && length > definition.maxLength) return `文字最多為 ${definition.maxLength} 字元。`
    if (definition.allowedValues.length && !definition.allowedValues.includes(value.normalize('NFC'))) return '請選擇核准的文字值。'
    return null
  }
  if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) return '請輸入一般十進位數字，不使用指數、空格或前導零。'
  if (definition.decimalScale !== null && (value.split('.')[1]?.length ?? 0) > definition.decimalScale) return `小數最多為 ${definition.decimalScale} 位。`
  const compare = (a: string, b: string) => {
    const scale = Math.max(a.split('.')[1]?.length ?? 0, b.split('.')[1]?.length ?? 0)
    const integer = (text: string) => { const [whole, fraction = ''] = text.replace('-', '').split('.'); return BigInt(`${text.startsWith('-') ? '-' : ''}${whole}${fraction.padEnd(scale, '0')}`) }
    return integer(a) < integer(b) ? -1 : integer(a) > integer(b) ? 1 : 0
  }
  if (definition.minValue !== null && compare(value, definition.minValue) < 0) return `數值不得小於 ${definition.minValue}。`
  if (definition.maxValue !== null && compare(value, definition.maxValue) > 0) return `數值不得大於 ${definition.maxValue}。`
  return null
}
