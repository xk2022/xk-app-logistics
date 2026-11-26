import { ApiError } from './api'
import { ClosureApiError, closureBusinessReasons, validManagementReason } from './closureApi'
import type { CloseBody, ClosureResult, ConfirmationDetail, ConfirmationResult, ConfirmBody, ManagementWrite, OrderClosure } from './closureApi'
export type ManagementContext = { kind: 'confirm'; detail: ConfirmationDetail } | { kind: 'close'; detail: OrderClosure }
export type ManagementReceipt = { kind: 'confirm'; result: ManagementWrite<ConfirmationResult> } | { kind: 'close'; result: ManagementWrite<ClosureResult> }
export type ManagementCommand = ({ kind: 'confirm'; id: string; body: ConfirmBody; original: ConfirmationDetail } | { kind: 'close'; id: string; body: CloseBody; original: OrderClosure }) & { key: string; unresolved?: true }
export const confirmationLabels = { PENDING: '待管理確認', CONFIRMED: '已管理確認' } as const
export const auditLabels = { NOT_CLOSED: '尚未結案', RECORDED: '已結案・有管理紀錄', LEGACY_CLOSED: '已結案・無管理結案紀錄' } as const
export const lifecycleLabels = { OPEN: '開啟', CLOSED: '已結案', CANCELLED: '已取消' } as const
export const phaseLabels = { WAITING: '待出發', DEPARTED: '已出發', IN_PROGRESS: '執行中', AWAITING_COMPLETION: '待完成確認', COMPLETED: '已完成' } as const
export const managementBlockers: Record<string, string> = { ALREADY_CONFIRMED: '這筆完成已記錄管理確認', ALREADY_CLOSED: '訂單已結案', POLICY_NOT_CONFIRMED: '目前管理政策尚未核准', ORDER_NOT_OPEN: '訂單目前未開啟', ORDER_NOT_ACCEPTED: '訂單尚未正式受理', SOURCE_VERSION_MISMATCH: '完成來源與訂單版本不一致', UNRESOLVED_EXCEPTIONS: '仍有未處置的阻擋異常', PROOF_INVALID: '完成與管理紀錄尚無法完整核實', TASKS_INCOMPLETE: '仍有未完成的任務', CONFIRMATION_REQUIRED: '仍需管理確認', RESOURCES_OCCUPIED: '本單仍有有效資源占用' }
export function managementSafeExit(e: unknown) { return e instanceof ApiError && (e.status === 401 || e.status === 403 && !(e instanceof ClosureApiError && e.reason === 'CSRF_INVALID')) }
export function settlesManagementAttempt(e: unknown, kind: 'confirm' | 'close') {
  if (!(e instanceof ClosureApiError) || e.status !== 409 || e.uncertain || !['E2101', 'E2102', 'E2103', 'E2107', 'E2108', 'E2109', 'E2110'].includes(e.code) || e.reason !== closureBusinessReasons[e.code as keyof typeof closureBusinessReasons] || Object.keys(e.fields).length > 0) return false
  if (e.code !== 'E2101') return e.actualConfirmationVersion === undefined && e.actualOrderVersion === undefined
  return kind === 'confirm' ? Number.isSafeInteger(e.actualConfirmationVersion) && e.actualConfirmationVersion! >= 0 && e.actualConfirmationVersion! <= 1 && e.actualOrderVersion === undefined : Number.isSafeInteger(e.actualOrderVersion) && e.actualOrderVersion! >= 0 && e.actualConfirmationVersion === undefined
}
export function freezeManagement<T>(v: T): T { const copy = structuredClone(v); const deep = (e: unknown) => { if (e && typeof e === 'object') { Object.values(e).forEach(deep); Object.freeze(e) } }; deep(copy); return copy }
export function canManage(context: ManagementContext) { return context.kind === 'confirm' ? context.detail.policy.canConfirm && context.detail.policy.mode === 'SYNTHETIC_TEST_ONLY' && context.detail.record.version === 0 : context.detail.policy.canClose && context.detail.policy.mode === 'SYNTHETIC_TEST_ONLY' && context.detail.version < Number.MAX_SAFE_INTEGER }
export function prepareManagement(context: ManagementContext, reason: string): Omit<Extract<ManagementCommand, {kind:'confirm'}>, 'key' | 'unresolved'> | Omit<Extract<ManagementCommand, {kind:'close'}>, 'key' | 'unresolved'> {
  if (!canManage(context)) throw new ClosureApiError(400, 'CLIENT_VALIDATION_ERROR', '目前政策或狀態尚未允許此操作。')
  if (!validManagementReason(reason)) throw new ClosureApiError(400, 'CLIENT_VALIDATION_ERROR', '請填寫 1–1000 字元的非空白原因，原文不會被自動改寫。', { fields: { reason: '請填寫有效原因。' } })
  return context.kind === 'confirm' ? freezeManagement({ kind: 'confirm', id: context.detail.record.completionId, body: { expectedVersion: context.detail.record.version, confirmCompletion: true, reason }, original: context.detail }) : freezeManagement({ kind: 'close', id: context.detail.orderId, body: { expectedVersion: context.detail.version, confirmClosure: true, reason }, original: context.detail })
}
export function taskReviewSource(context: ManagementContext): number | null { if (context.kind !== 'close') return null; const d = context.detail; return d.auditState === 'RECORDED' ? d.closure!.sourceVersion : d.lifecycleStatus === 'OPEN' && d.acceptanceStatus === 'ACCEPTED' ? d.counts!.sourceOrderVersion : null }
