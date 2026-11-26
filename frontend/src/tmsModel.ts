import { TmsApiError } from './tmsApi'
import type { Assignment, AssignRequest, Blocker, DispatchDetail, ReassignRequest, ResourceCandidate, RevokeRequest, TaskSummary } from './tmsApi'

export const blockerLabels: Record<Blocker, string> = { SOURCE_NOT_ACCEPTED: '來源已變更或尚未受理', PREREQUISITE_NOT_READY: '前置任務尚未就緒', POLICY_NOT_CONFIRMED: '派工政策尚未核准', ALREADY_ASSIGNED: '已有派工記錄', ADMIN_UNAVAILABLE: '行政來源不可用', RESOURCE_OCCUPIED: '資源已被占用' }
export const assignmentLabels = { ASSIGNED: '已建立派工記錄', REVOKED: '已撤派', SUPERSEDED: '已由新派工取代' } as const
export const actionLabels = { assign: '指派', revoke: '撤派', reassign: '改派' } as const
export type CommandMode = keyof typeof actionLabels
export type DispatchCommand = { kind: 'assign'; taskId: string; body: AssignRequest } | { kind: 'revoke'; taskId: string; assignmentId: string; body: RevokeRequest } | { kind: 'reassign'; taskId: string; assignmentId: string; body: ReassignRequest }
export type PendingDispatchCommand = DispatchCommand & { key: string; unresolved?: true }
export function safeExitRequired(error: unknown): boolean { return error instanceof TmsApiError && (error.status === 401 || error.status === 403 && error.reason !== 'CSRF_INVALID') }
// These named business failures occur only after successful-key replay misses in TmsService.
// Shape/no-op share E0400, so no E0400 can settle a previously unacknowledged write.
export function settlesPriorDispatch(error: unknown): boolean {
  if (!(error instanceof TmsApiError) || error.status !== 409 || error.uncertain) return false
  const reasons: Record<string, string> = { E1601: 'VERSION_CONFLICT', E1602: 'RESOURCE_OCCUPIED', E1603: 'INVALID_STATE', E1604: 'PREREQUISITE_NOT_READY', E1605: 'POLICY_NOT_CONFIRMED', E1606: 'MASTER_UNAVAILABLE', E1609: 'PROTECTED_RELATION' }
  return Object.hasOwn(reasons, error.code) && reasons[error.code] === error.reason
}
export function sourceAllowsNewDispatch(task: TaskSummary, reassign = false): boolean {
  return task.policyMode === 'SYNTHETIC_TEST_ONLY' && task.prerequisiteReady && task.blockingReasons.every((reason) => reassign && reason === 'ALREADY_ASSIGNED')
}
export function candidateSelectable(candidate: ResourceCandidate, taskId: string, assignmentId?: string): boolean {
  return candidate.administrativelyAvailable && candidate.policyMode === 'SYNTHETIC_TEST_ONLY' && candidate.policyApproved && candidate.blockingReasons.length === 0
    && (!candidate.occupied || Boolean(assignmentId && candidate.occupation?.assignmentId === assignmentId && candidate.occupation.taskId === taskId))
}
function outerSpace(character: string): boolean { const cp = character.codePointAt(0)!; return /\p{White_Space}/u.test(character) && cp !== 0x85 || cp >= 0x1c && cp <= 0x1f || cp === 0xfeff }
export function normalizeTmsText(value: string): string {
  const points = [...value.normalize('NFC')]; let start = 0, end = points.length
  while (start < end && outerSpace(points[start])) start++
  while (end > start && outerSpace(points[end - 1])) end--
  return points.slice(start, end).join('')
}
function validReason(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const cp = value.charCodeAt(index)
    if (cp <= 0x1f && ![9, 10, 13].includes(cp) || cp >= 0x7f && cp <= 0x9f) return false
    if (cp >= 0xd800 && cp <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false }
    else if (cp >= 0xdc00 && cp <= 0xdfff) return false
  }
  return true
}
function validation(fields: Record<string, string>): never { throw new TmsApiError(400, 'E0400', '請確認派工操作欄位。', { reason: 'VALIDATION_ERROR', fields }) }
export function makeDispatchCommand(mode: CommandMode, detail: DispatchDetail, selection: { driverId: string; mainVehicleId: string; trailerId: string; reason: string }): DispatchCommand {
  const generation = detail.task.generation
  const current = detail.current
  const fields: Record<string, string> = {}
  if (!Number.isSafeInteger(generation) || generation < 0 || generation >= Number.MAX_SAFE_INTEGER) fields.expectedGeneration = '任務派工版本已達上限，請重新讀取並確認。'
  if (mode !== 'assign' && (!current || current.status !== 'ASSIGNED')) fields.assignmentId = '目前沒有可撤派或改派的派工記錄，請重新讀取。'
  if (mode === 'assign' && current) fields.assignmentId = '此任務已有派工記錄，請重新讀取。'
  if (mode !== 'revoke') {
    if (!sourceAllowsNewDispatch(detail.task, mode === 'reassign')) fields.policy = '目前來源、前置任務或派工政策尚未允許此新派工。'
    for (const name of ['driverId', 'mainVehicleId'] as const) if (!selection[name]) fields[name] = name === 'driverId' ? '請選擇司機。' : '請選擇主車。'
    if (selection.mainVehicleId && selection.mainVehicleId === selection.trailerId) fields.trailerId = '主車與拖板不能使用同一車輛。'
    if (mode === 'reassign' && current && current.driver.id === selection.driverId && current.mainVehicle.id === selection.mainVehicleId && (current.trailer?.id ?? '') === selection.trailerId) fields.resources = '改派必須至少變更一項資源。'
  }
  if (mode !== 'assign') { const reason = normalizeTmsText(selection.reason); if (!validReason(reason) || [...reason].length < 1 || [...reason].length > 500) fields.reason = '原因正規化後須為 1–500 字元，可含換行、Tab，不可含其他控制字元。' }
  if (Object.keys(fields).length) validation(fields)
  const resources = { driverId: selection.driverId, mainVehicleId: selection.mainVehicleId, trailerId: selection.trailerId || null }
  if (mode === 'assign') return { kind: mode, taskId: detail.task.taskId, body: { expectedGeneration: generation, ...resources } }
  if (mode === 'revoke') return { kind: mode, taskId: detail.task.taskId, assignmentId: current!.id, body: { expectedGeneration: generation, reason: selection.reason } }
  return { kind: mode, taskId: detail.task.taskId, assignmentId: current!.id, body: { expectedGeneration: generation, ...resources, reason: selection.reason } }
}
export function commandResources(assignment: Assignment): { driverId: string; mainVehicleId: string; trailerId: string } { return { driverId: assignment.driver.id, mainVehicleId: assignment.mainVehicle.id, trailerId: assignment.trailer?.id ?? '' } }
export function immutableSnapshot<T>(value: T): T {
  const cloned = structuredClone(value)
  const freeze = (entry: unknown) => { if (entry && typeof entry === 'object') { Object.values(entry).forEach(freeze); Object.freeze(entry) } }
  freeze(cloned); return cloned
}
