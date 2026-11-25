import { ApiError } from './api'
import type { Vehicle, VehicleCreate, AdministrativeStatus } from './vehicleApi'

export const vehicleStatusLabels: Record<AdministrativeStatus, string> = { AVAILABLE: '可用', MAINTENANCE: '維修', INACTIVE: '停用' }
export interface VehicleFields { plateNo: string; vehicleType: string; capacityTon: string; registrationNo: string; registrationDate: string; inspectionDueDate: string; administrativeStatus: AdministrativeStatus }
export function vehicleFields(vehicle?: Vehicle): VehicleFields {
  return { plateNo: vehicle?.plateNo ?? '', vehicleType: vehicle?.vehicleType ?? '', capacityTon: vehicle?.capacityTon ?? '', registrationNo: vehicle?.registrationNo ?? '', registrationDate: vehicle?.registrationDate ?? '', inspectionDueDate: vehicle?.inspectionDueDate ?? '', administrativeStatus: vehicle?.administrativeStatus ?? 'AVAILABLE' }
}
function boundarySpace(value: string): boolean { const code = value.codePointAt(0)!; return /\p{White_Space}/u.test(value) && code !== 0x85 || code >= 0x1c && code <= 0x1f || code === 0xfeff }
export function stripVehicleText(value: string): string {
  const points = [...value]; let start = 0, end = points.length
  while (start < end && boundarySpace(points[start])) start++
  while (end > start && boundarySpace(points[end - 1])) end--
  return points.slice(start, end).join('').normalize('NFC')
}
export function canonicalPlatePreview(value: string): string { return stripVehicleText(value.normalize('NFC')).toUpperCase().normalize('NFC') }
function invalidText(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code <= 0x1f || code >= 0x7f && code <= 0x9f) return true
    if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return true }
    else if (code >= 0xdc00 && code <= 0xdfff) return true
  }
  return false
}
export function validVehicleDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1).map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
export function vehiclePayload(fields: VehicleFields): VehicleCreate {
  const errors: Record<string, string> = {}
  const plate = canonicalPlatePreview(fields.plateNo)
  if (invalidText(plate) || [...plate].length < 1 || [...plate].length > 40) errors.plateNo = '車牌正規化後須為 1–40 字元，不可含控制字元；保留內部空格與連字號。'
  if (/^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.exec(fields.vehicleType)?.[0] !== fields.vehicleType) errors.vehicleType = '請選擇車種。'
  if (fields.capacityTon !== '' && (fields.capacityTon.length > 32 || /^(0|[1-9]\d*)(\.\d+)?$/.exec(fields.capacityTon)?.[0] !== fields.capacityTon)) errors.capacityTon = '載重須為最多 32 字的非負十進位文字，不使用指數或前導零；留空表示未知。'
  const registration = stripVehicleText(fields.registrationNo)
  if (invalidText(registration) || [...registration].length > 100) errors.registrationNo = '車籍編號最多 100 字元，不可含控制字元；不限定國別格式。'
  for (const name of ['registrationDate', 'inspectionDueDate'] as const) if (fields[name] !== '' && !validVehicleDate(fields[name])) errors[name] = '請填有效 YYYY-MM-DD 自然日（0001–9999 年），或留空。'
  if (!Object.hasOwn(vehicleStatusLabels, fields.administrativeStatus)) errors.administrativeStatus = '請選擇合法管理狀態。'
  if (Object.keys(errors).length) throw new ApiError(400, '請確認車輛欄位。', errors)
  return { ...fields, plateNo: fields.plateNo, capacityTon: fields.capacityTon === '' ? null : fields.capacityTon, registrationNo: fields.registrationNo === '' ? null : fields.registrationNo, registrationDate: fields.registrationDate === '' ? null : fields.registrationDate, inspectionDueDate: fields.inspectionDueDate === '' ? null : fields.inspectionDueDate }
}
const dateInTaipei = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit' })
export function inspectionHint(value: string | null, now = new Date()): string | null {
  if (value === null) return null
  const parts = dateInTaipei.formatToParts(now)
  const today = ['year', 'month', 'day'].map((name) => parts.find((part) => part.type === name)?.value).join('-')
  return value < today ? '已過記錄日期，請確認資料。' : value === today ? '記錄期限為今日。' : null
}
