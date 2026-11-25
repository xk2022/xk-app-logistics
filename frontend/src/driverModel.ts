import { ApiError } from './api'
import type { Driver, DriverWrite, DutyStatus, EmploymentStatus } from './driverApi'

export const employmentLabels: Record<EmploymentStatus, string> = { ACTIVE: '在職', INACTIVE: '停用', LEFT: '離職' }
export const dutyLabels: Record<DutyStatus, string> = { ON_DUTY: '出勤', OFF_DUTY: '未出勤' }
export interface DriverFields { driverCode: string; name: string; phone: string; email: string; accountId: string; employmentStatus: EmploymentStatus; dutyStatus: DutyStatus; licenseType: string; licenseReference: string; licenseDueDate: string }
export function driverFields(driver?: Driver): DriverFields {
  return { driverCode: driver?.driverCode ?? '', name: driver?.name ?? '', phone: driver?.phone ?? '', email: driver?.email ?? '', accountId: driver?.accountId ?? '', employmentStatus: driver?.employmentStatus ?? 'ACTIVE', dutyStatus: driver?.dutyStatus ?? 'OFF_DUTY', licenseType: driver?.licenseType ?? '', licenseReference: driver?.licenseReference ?? '', licenseDueDate: driver?.licenseDueDate ?? '' }
}
function boundarySpace(character: string): boolean { const code = character.codePointAt(0)!; return /\p{White_Space}/u.test(character) && code !== 0x85 || code >= 0x1c && code <= 0x1f || code === 0xfeff }
export function stripDriverText(value: string): string {
  const points = [...value]; let start = 0, end = points.length
  while (start < end && boundarySpace(points[start])) start++
  while (end > start && boundarySpace(points[end - 1])) end--
  return points.slice(start, end).join('').normalize('NFC')
}
export function canonicalDriverCode(value: string): string { return stripDriverText(value.normalize('NFC')).toUpperCase().normalize('NFC') }
function invalidText(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code <= 0x1f || code >= 0x7f && code <= 0x9f) return true
    if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return true }
    else if (code >= 0xdc00 && code <= 0xdfff) return true
  }
  return false
}
export function validDriverDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1).map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
export function driverPayload(fields: DriverFields, creating: boolean): DriverWrite {
  const errors: Record<string, string> = {}
  if (creating) { const code = canonicalDriverCode(fields.driverCode); if (invalidText(code) || [...code].length < 1 || [...code].length > 40) errors.driverCode = '司機編號正規化後須為 1–40 字元，不可含控制字元；建立後不可修改。' }
  const limits = { name: 200, phone: 50, email: 254, licenseReference: 100 } as const
  for (const name of Object.keys(limits) as (keyof typeof limits)[]) {
    const value = stripDriverText(fields[name])
    if (invalidText(value) || [...value].length > limits[name] || name === 'name' && value === '') errors[name] = `${name === 'name' ? '姓名' : name === 'phone' ? '電話' : name === 'email' ? '電子郵件' : '證照參考說明'}${name === 'name' ? '須為 1–' : '最多 '}${limits[name]} 字元，不可含控制字元。`
  }
  for (const name of ['accountId', 'licenseType'] as const) if (fields[name] !== '' && /^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.exec(fields[name])?.[0] !== fields[name]) errors[name] = '請選擇有效選項，或留空。'
  if (fields.licenseDueDate !== '' && !validDriverDate(fields.licenseDueDate)) errors.licenseDueDate = '請填有效 YYYY-MM-DD 自然日（0001–9999 年），或留空。'
  if (!Object.hasOwn(employmentLabels, fields.employmentStatus)) errors.employmentStatus = '請選擇合法在職狀態。'
  if (!Object.hasOwn(dutyLabels, fields.dutyStatus)) errors.dutyStatus = '請選擇合法出勤狀態。'
  if (Object.keys(errors).length) throw new ApiError(400, '請確認司機欄位。', errors)
  const optional = (value: string) => value === '' ? null : value
  return { name: fields.name, phone: optional(fields.phone), email: optional(fields.email), accountId: optional(fields.accountId), employmentStatus: fields.employmentStatus, dutyStatus: fields.dutyStatus, licenseType: optional(fields.licenseType), licenseReference: optional(fields.licenseReference), licenseDueDate: optional(fields.licenseDueDate) }
}
const dateInTaipei = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit' })
export function licenseDateHint(value: string | null, now = new Date()): string | null {
  if (value === null) return null
  const parts = dateInTaipei.formatToParts(now)
  const today = ['year', 'month', 'day'].map((name) => parts.find((part) => part.type === name)?.value).join('-')
  return value < today ? '已過記錄日期，請確認資料。' : value === today ? '記錄期限為今日。' : null
}
