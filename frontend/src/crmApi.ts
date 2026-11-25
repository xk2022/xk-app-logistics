import { ApiError, request, write } from './api'
import type { PageData } from './admApi'

export interface Customer {
  id: string; customerCode: string; name: string; taxId: string | null; phone: string | null; email: string | null; enabled: boolean; version: number
}
export interface Contact {
  id: string; customerId: string; name: string; title: string | null; phone: string | null; email: string | null; enabled: boolean; version: number
}
export interface CustomerAddress {
  id: string; customerId: string; label: string | null; address: string; addressType: string | null; enabled: boolean; version: number
}
export interface CustomerOption { id: string; customerCode: string; name: string; version: number }
export interface CustomerQuery { q: string; enabled: 'all' | 'true' | 'false'; size?: 5 | 20 | 50 }
export type CustomerWrite = Pick<Customer, 'name' | 'taxId' | 'phone' | 'email' | 'enabled'>
export type ContactWrite = Pick<Contact, 'name' | 'title' | 'phone' | 'email' | 'enabled'>
export type AddressWrite = Pick<CustomerAddress, 'label' | 'address' | 'addressType' | 'enabled'>

function record(value: unknown): value is Record<string, unknown> { return Boolean(value) && typeof value === 'object' && !Array.isArray(value) }
function invalid(): never { throw new ApiError(0, '客戶資料回應格式不正確，請重新載入。') }
function optional(value: unknown): value is string | null { return value === null || typeof value === 'string' }
function base(value: unknown): value is Record<string, unknown> { return record(value) && typeof value.id === 'string' && typeof value.enabled === 'boolean' && Number.isInteger(value.version) && (value.version as number) >= 0 }
function customer(value: unknown): Customer {
  if (!base(value) || typeof value.customerCode !== 'string' || typeof value.name !== 'string' || !['taxId', 'phone', 'email'].every((key) => optional(value[key]))) invalid()
  return value as unknown as Customer
}
function contact(value: unknown): Contact {
  if (!base(value) || typeof value.customerId !== 'string' || typeof value.name !== 'string' || !['title', 'phone', 'email'].every((key) => optional(value[key]))) invalid()
  return value as unknown as Contact
}
function address(value: unknown): CustomerAddress {
  if (!base(value) || typeof value.customerId !== 'string' || typeof value.address !== 'string' || !optional(value.label) || !optional(value.addressType)) invalid()
  return value as unknown as CustomerAddress
}
function option(value: unknown): CustomerOption {
  if (!record(value) || !['id', 'customerCode', 'name'].every((key) => typeof value[key] === 'string') || !Number.isInteger(value.version) || (value.version as number) < 0) invalid()
  return value as unknown as CustomerOption
}
async function pageRequest<T>(path: string, parse: (value: unknown) => T, page: number, query: CustomerQuery, signal?: AbortSignal): Promise<PageData<T>> {
  const params = new URLSearchParams({ page: String(page), size: String(query.size ?? 20) })
  if (query.q) params.set('q', query.q)
  if (query.enabled !== 'all') params.set('enabled', query.enabled)
  const data = await request(`${path}?${params}`, 'GET', undefined, signal)
  if (!record(data) || !Array.isArray(data.content) || !['page', 'size', 'totalElements', 'totalPages'].every((key) => Number.isInteger(data[key]) && (data[key] as number) >= 0) || data.size === 0) invalid()
  return { content: data.content.map(parse), page: data.page as number, size: data.size as number, totalElements: data.totalElements as number, totalPages: data.totalPages as number }
}
const all: CustomerQuery = { q: '', enabled: 'all' }
const pathFor = (id: string) => `/crm/customers/${encodeURIComponent(id)}`
export const getCustomers = (page: number, query: CustomerQuery, signal?: AbortSignal) => pageRequest('/crm/customers', customer, page, query, signal)
export const getCustomer = async (id: string, signal?: AbortSignal) => customer(await request(pathFor(id), 'GET', undefined, signal))
export const createCustomer = async (data: Pick<Customer, 'customerCode' | 'name'> & Partial<Omit<CustomerWrite, 'name'>>) => customer(await write('/crm/customers', 'POST', data))
export const updateCustomer = async (id: string, data: Partial<CustomerWrite> & { version: number }) => customer(await write(pathFor(id), 'PATCH', data))
export const getContacts = (id: string, page: number, signal?: AbortSignal) => pageRequest(`${pathFor(id)}/contacts`, contact, page, all, signal)
export const createContact = async (id: string, data: Pick<ContactWrite, 'name'> & Partial<Omit<ContactWrite, 'name'>>) => contact(await write(`${pathFor(id)}/contacts`, 'POST', data))
export const updateContact = async (id: string, contactId: string, data: Partial<ContactWrite> & { version: number }) => contact(await write(`${pathFor(id)}/contacts/${encodeURIComponent(contactId)}`, 'PATCH', data))
export const getAddresses = (id: string, page: number, signal?: AbortSignal) => pageRequest(`${pathFor(id)}/addresses`, address, page, all, signal)
export const createAddress = async (id: string, data: Pick<AddressWrite, 'address'> & Partial<Omit<AddressWrite, 'address'>>) => address(await write(`${pathFor(id)}/addresses`, 'POST', data))
export const updateAddress = async (id: string, addressId: string, data: Partial<AddressWrite> & { version: number }) => address(await write(`${pathFor(id)}/addresses/${encodeURIComponent(addressId)}`, 'PATCH', data))
export const getCustomerOptions = (page: number, q: string, signal?: AbortSignal) => pageRequest('/crm/customer-options', option, page, { q, enabled: 'all' }, signal)

export function nullable(value: string): string | null { return value.trim() === '' ? null : value.trim().normalize('NFC') }
export function validateText(value: string, label: string, maximum: number, required = false): void {
  const length = [...value.trim().normalize('NFC')].length
  if (length > maximum || required && length === 0) throw new ApiError(400, `${label}${required ? '須為 1–' : '最多為 '}${maximum} 字元。`)
}
export function validateEmail(value: string): void {
  validateText(value, '電子郵件', 254)
  if (value.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())) throw new ApiError(400, '請輸入基本格式正確的電子郵件，或留空。')
}
