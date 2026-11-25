import type { Order, OrderDraft } from './tomApi'

export interface EditorShipping { shippingCompany: string; vesselVoyage: string; pol: string; pod: string; eta: string; etd: string; blNo: string }
export interface EditorItem {
  clientRef: string; sequence: string; itemKind: '' | 'CONTAINER' | 'CARGO'; containerType: string; containerNo: string;
  packageQty: string; grossWeight: string; cbm: string; lastPickupDate: string; deliveryCutoffAt: string; serverId?: string
}
export interface EditorTask {
  clientRef: string; itemRef: string; taskKind: string; pickupName: string; pickupAddress: string; deliveryName: string; deliveryAddress: string;
  siteContact: { name: string; phone: string } | null; plannedStartAt: string; plannedEndAt: string;
  predecessorRefs: string[]; requiredTask: '' | 'true'; serverId?: string; taskNo?: string
}
export interface EditorDraft {
  type: '' | 'IMPORT' | 'EXPORT' | 'LOCAL'; customerId: string; customerReference: string; remark: string | null;
  shipping: EditorShipping | null; items: EditorItem[]; tasks: EditorTask[]
}
export interface EditorIssue { path: string; message: string; fieldId?: string }
export class EditorValidationError extends Error {
  constructor(public issues: EditorIssue[]) { super('請修正草稿格式或關聯；不必先填完受理欄位。') }
}
export const emptyShipping = (): EditorShipping => ({ shippingCompany: '', vesselVoyage: '', pol: '', pod: '', eta: '', etd: '', blNo: '' })
export const newItem = (): EditorItem => ({ clientRef: `item-${crypto.randomUUID()}`, sequence: '', itemKind: '', containerType: '', containerNo: '', packageQty: '', grossWeight: '', cbm: '', lastPickupDate: '', deliveryCutoffAt: '' })
export const newTask = (itemRef = ''): EditorTask => ({ clientRef: `task-${crypto.randomUUID()}`, itemRef, taskKind: '', pickupName: '', pickupAddress: '', deliveryName: '', deliveryAddress: '', siteContact: null, plannedStartAt: '', plannedEndAt: '', predecessorRefs: [], requiredTask: '' })

export function editorFromOrder(order?: Order): EditorDraft {
  if (!order) return { type: '', customerId: '', customerReference: '', remark: null, shipping: null, items: [], tasks: [] }
  return {
    type: order.type, customerId: order.customerId, customerReference: order.customerReference ?? '', remark: order.remark,
    shipping: order.shipping === null ? null : {
      shippingCompany: order.shipping.shippingCompany ?? '', vesselVoyage: order.shipping.vesselVoyage ?? '', pol: order.shipping.pol ?? '', pod: order.shipping.pod ?? '', eta: order.shipping.eta ?? '', etd: order.shipping.etd ?? '', blNo: order.shipping.blNo ?? '',
    },
    items: order.items.map((item) => ({ clientRef: item.clientRef, serverId: item.id, sequence: item.sequence === null ? '' : String(item.sequence), itemKind: item.itemKind ?? '', containerType: item.containerType ?? '', containerNo: item.containerNo ?? '', packageQty: item.packageQty === null ? '' : String(item.packageQty), grossWeight: item.grossWeight ?? '', cbm: item.cbm ?? '', lastPickupDate: item.lastPickupDate ?? '', deliveryCutoffAt: item.deliveryCutoffAt ?? '' })),
    tasks: order.tasks.map((task) => ({ clientRef: task.clientRef, serverId: task.id, taskNo: task.taskNo, itemRef: task.itemRef, taskKind: task.taskKind ?? '', pickupName: task.pickupName ?? '', pickupAddress: task.pickupAddress ?? '', deliveryName: task.deliveryName ?? '', deliveryAddress: task.deliveryAddress ?? '', siteContact: task.siteContact === null ? null : { name: task.siteContact.name ?? '', phone: task.siteContact.phone ?? '' }, plannedStartAt: task.plannedStartAt ?? '', plannedEndAt: task.plannedEndAt ?? '', predecessorRefs: [...task.predecessorRefs], requiredTask: task.requiredTask === true ? 'true' : '' })),
  }
}

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/
const TIME = /^(\d{4})-(\d{2})-(\d{2})[Tt]([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,6}))?([Zz]|([+-])([01]\d|2[0-3]):([0-5]\d))$/
export function validWireDate(value: string): boolean {
  const match = DATE.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1).map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
export function wireInstant(value: string): bigint | null {
  const match = TIME.exec(value)
  if (!match || match[0] !== value || !validWireDate(`${match[1]}-${match[2]}-${match[3]}`)) return null
  const date = new Date(0)
  date.setUTCFullYear(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  date.setUTCHours(Number(match[4]), Number(match[5]), Number(match[6]), 0)
  const offset = match[8].toUpperCase() === 'Z' ? 0 : (Number(match[10]) * 60 + Number(match[11])) * (match[9] === '+' ? 1 : -1)
  return BigInt(date.getTime() - offset * 60000) * 1000n + BigInt((match[7] ?? '').padEnd(6, '0'))
}
const taipei = new Intl.DateTimeFormat('zh-TW', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
export function taipeiPreview(value: string): string | null {
  const micros = wireInstant(value)
  if (micros === null) return null
  const remainder = (micros % 1000n + 1000n) % 1000n
  const formatted = taipei.format(new Date(Number((micros - remainder) / 1000n)))
  const fraction = TIME.exec(value)?.[7]
  return fraction ? `${formatted}.${fraction}` : formatted
}

function validUnicode(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false }
    else if (code >= 0xdc00 && code <= 0xdfff) return false
  }
  return true
}
function positiveInteger(value: string): number | null {
  if (value === '') return null
  const match = /^(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value)
  if (!match || match[0] !== value) return NaN
  const fraction = match[2] ?? ''
  let digits = (match[1] + fraction).replace(/^0+/, '') || '0'
  const shift = BigInt(match[3] ?? '0') - BigInt(fraction.length)
  if (shift >= 0n) { if (shift > 10n) return NaN; digits += '0'.repeat(Number(shift)) }
  else {
    if (-shift > BigInt(digits.length)) return NaN
    const cut = Number(-shift)
    if (!/^0*$/.test(digits.slice(digits.length - cut))) return NaN
    digits = digits.slice(0, digits.length - cut) || '0'
  }
  const number = BigInt(digits)
  return number >= 1n && number <= 2147483647n ? Number(number) : NaN
}
export function toOrderDraft(value: EditorDraft): OrderDraft {
  const issues: EditorIssue[] = []
  const add = (path: string, message: string) => { issues.push({ path, message }) }
  const text = (raw: string, path: string, maximum = 200): string | null => {
    if (raw === '') return null
    if (!validUnicode(raw) || [...raw].length > maximum || !/\S/u.test(raw)) add(path, `請保留合法文字（最多 ${maximum} 字），不可只含空白；清空表示未知。`)
    return raw
  }
  const integer = (raw: string, path: string) => { const result = positiveInteger(raw); if (Number.isNaN(result)) add(path, '請填 1–2147483647 的精確正整數，或留空表示未知。'); return result }
  const decimal = (raw: string, path: string) => { if (raw !== '' && (raw.length > 32 || /^(0|[1-9]\d*)(\.\d+)?$/.exec(raw)?.[0] !== raw)) add(path, '請填最多 32 字的非負十進位文字，不使用前導零或指數；留空不是 0。'); return raw === '' ? null : raw }
  const date = (raw: string, path: string) => { if (raw !== '' && !validWireDate(raw)) add(path, '請填有效 YYYY-MM-DD 日期，或留空。'); return raw === '' ? null : raw }
  const time = (raw: string, path: string) => { if (raw !== '' && wireInstant(raw) === null) add(path, '請填含時區的 RFC3339 日期時間，小數秒最多六位，或留空。'); return raw === '' ? null : raw }
  if (!['IMPORT', 'EXPORT', 'LOCAL'].includes(value.type)) add('/type', '請選擇訂單類型。')
  if (/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.exec(value.customerId)?.[0] !== value.customerId) add('/customerId', '請選擇客戶。')
  if (value.remark !== null && (!validUnicode(value.remark) || [...value.remark].length > 4000)) add('/remark', '備註最多 4000 字，並須為有效 Unicode 文字。')
  const shipping = value.shipping === null ? null : {
    shippingCompany: text(value.shipping.shippingCompany, '/shipping/shippingCompany'), vesselVoyage: text(value.shipping.vesselVoyage, '/shipping/vesselVoyage'), pol: text(value.shipping.pol, '/shipping/pol'), pod: text(value.shipping.pod, '/shipping/pod'), eta: time(value.shipping.eta, '/shipping/eta'), etd: time(value.shipping.etd, '/shipping/etd'), blNo: text(value.shipping.blNo, '/shipping/blNo'),
  }
  const seenItems = new Set<string>(), seenTasks = new Set<string>(), sequences = new Set<number>()
  const items = value.items.map((item, index) => {
    const path = `/items/${index}`
    if (/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.exec(item.clientRef)?.[0] !== item.clientRef || seenItems.has(item.clientRef)) add(`${path}/clientRef`, '項目參照無效或重複。')
    seenItems.add(item.clientRef)
    const sequence = integer(item.sequence, `${path}/sequence`)
    if (sequence !== null && !Number.isNaN(sequence)) { if (sequences.has(sequence)) add(`${path}/sequence`, '同一訂單的項目序號不可重複。'); sequences.add(sequence) }
    return { clientRef: item.clientRef, sequence, itemKind: item.itemKind === '' ? null : item.itemKind, containerType: text(item.containerType, `${path}/containerType`), containerNo: text(item.containerNo, `${path}/containerNo`), packageQty: integer(item.packageQty, `${path}/packageQty`), grossWeight: decimal(item.grossWeight, `${path}/grossWeight`), cbm: decimal(item.cbm, `${path}/cbm`), lastPickupDate: date(item.lastPickupDate, `${path}/lastPickupDate`), deliveryCutoffAt: time(item.deliveryCutoffAt, `${path}/deliveryCutoffAt`) }
  })
  const tasks = value.tasks.map((task, index) => {
    const path = `/tasks/${index}`
    if (/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.exec(task.clientRef)?.[0] !== task.clientRef || seenTasks.has(task.clientRef)) add(`${path}/clientRef`, '任務參照無效或重複。')
    seenTasks.add(task.clientRef)
    if (!seenItems.has(task.itemRef)) add(`${path}/itemRef`, '請選擇本草稿內的項目。')
    const start = time(task.plannedStartAt, `${path}/plannedStartAt`), end = time(task.plannedEndAt, `${path}/plannedEndAt`)
    const startInstant = start === null ? null : wireInstant(start), endInstant = end === null ? null : wireInstant(end)
    if (startInstant !== null && endInstant !== null && endInstant < startInstant) add(`${path}/plannedEndAt`, '預定結束不得早於預定開始。')
    return { clientRef: task.clientRef, itemRef: task.itemRef, taskKind: text(task.taskKind, `${path}/taskKind`), pickupName: text(task.pickupName, `${path}/pickupName`), pickupAddress: text(task.pickupAddress, `${path}/pickupAddress`, 1000), deliveryName: text(task.deliveryName, `${path}/deliveryName`), deliveryAddress: text(task.deliveryAddress, `${path}/deliveryAddress`, 1000), siteContact: task.siteContact === null ? null : { name: text(task.siteContact.name, `${path}/siteContact/name`), phone: text(task.siteContact.phone, `${path}/siteContact/phone`, 50) }, plannedStartAt: start, plannedEndAt: end, predecessorRefs: [...task.predecessorRefs], requiredTask: task.requiredTask === 'true' ? true as const : null }
  })
  const graph = new Map(value.tasks.map((task) => [task.clientRef, task.predecessorRefs]))
  value.tasks.forEach((task, index) => {
    if (new Set(task.predecessorRefs).size !== task.predecessorRefs.length || task.predecessorRefs.some((ref) => ref === task.clientRef || !seenTasks.has(ref))) add(`/tasks/${index}/predecessorRefs`, '前置任務不可失聯、重複或指向自己。')
  })
  const indegrees = new Map(value.tasks.map((task) => [task.clientRef, 0]))
  const dependents = new Map<string, string[]>()
  for (const [ref, predecessors] of graph) for (const predecessor of predecessors) if (indegrees.has(predecessor)) { indegrees.set(ref, indegrees.get(ref)! + 1); dependents.set(predecessor, [...dependents.get(predecessor) ?? [], ref]) }
  const queue = [...indegrees].filter(([, count]) => count === 0).map(([ref]) => ref)
  for (let position = 0; position < queue.length; position++) for (const dependent of dependents.get(queue[position]) ?? []) { const count = indegrees.get(dependent)! - 1; indegrees.set(dependent, count); if (count === 0) queue.push(dependent) }
  if (queue.length !== indegrees.size) add('/tasks', '前置任務構成循環，請先調整依賴關係。')
  const result = { type: value.type, customerId: value.customerId, customerReference: text(value.customerReference, '/customerReference'), remark: value.remark, shipping, items, tasks }
  if (issues.length) throw new EditorValidationError(issues)
  return result as OrderDraft
}
