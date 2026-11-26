import { ApiError } from './api'
import { ExceptionApiError, exceptionBusinessReasons, nonblankExceptionText } from './exceptionApi'
import type { ActionBlocker, ExceptionDetail, ExceptionReasonCommand, ExceptionRecord, ExceptionStatus, ExceptionEventType, ExecutionPhase } from './exceptionApi'
export const exceptionStatusLabels: Record<ExceptionStatus, string> = { OPEN: '待處理', NEEDS_SUPPLEMENT: '待補件', RESOLVED: '已處置' }
export const exceptionPhaseLabels: Record<ExecutionPhase, string> = { WAITING: '待出發', DEPARTED: '已出發', IN_PROGRESS: '執行中', AWAITING_COMPLETION: '待完成確認', COMPLETED: '已完成' }
export const exceptionEventLabels: Record<ExceptionEventType, string> = { REPORTED: '原始回報', SUPPLEMENT_REQUESTED: '要求補件', SUPPLEMENT_EVIDENCE_UPLOADED: '上傳補件照片', SUPPLEMENT_SUBMITTED: '提交補件', RESOLVED: '異常處置' }
export const exceptionBlockerLabels: Record<ActionBlocker, string> = { FORBIDDEN: '此操作不屬於目前角色', POLICY_NOT_CONFIRMED: '處置政策或私有儲存尚未核准', INVALID_STATE: '目前狀態不支援此操作', SUPPLEMENT_REQUIRED: '尚需有效且已提交的補件', EVIDENCE_REQUIRED: '尚需有效的補件照片' }
export type ExceptionCommandMode = 'request' | 'resolve'
export interface PendingExceptionCommand { kind: ExceptionCommandMode; exceptionId: string; body: ExceptionReasonCommand; key: string; original: ExceptionRecord; unresolved?: true }
export function safeExceptionExit(error: unknown) { return error instanceof ApiError && (error.status === 401 || error.status === 403 && !(error instanceof ExceptionApiError && error.reason === 'CSRF_INVALID')) }
// Only a rejection of this SAME frozen POST can use this post-ledger-miss proof.
// Read failures never call this function or change the pending command.
export function settlesExceptionAttempt(error: unknown): boolean {
  if (!(error instanceof ExceptionApiError) || error.status !== 409 || error.uncertain || !['E1901', 'E1902', 'E1903', 'E1907', 'E1908'].includes(error.code) || error.reason !== exceptionBusinessReasons[error.code as keyof typeof exceptionBusinessReasons]) return false
  return error.code !== 'E1901' || Number.isSafeInteger(error.actualVersion) && error.actualExecutionVersion === undefined
}
export function freezeExceptionCommand<T>(value: T): T { const cloned = structuredClone(value); const freeze = (entry: unknown) => { if (entry && typeof entry === 'object') { Object.values(entry).forEach(freeze); Object.freeze(entry) } }; freeze(cloned); return cloned }
export function prepareExceptionCommand(mode: ExceptionCommandMode, detail: ExceptionDetail, reason: string): Omit<PendingExceptionCommand, 'key' | 'unresolved'> {
  const action = mode === 'request' ? detail.actions.requestSupplement : detail.actions.resolve
  if (!action.allowed || detail.actions.mode !== 'SYNTHETIC_TEST_ONLY' || detail.exception.status !== 'OPEN') throw new ExceptionApiError(400, 'CLIENT_VALIDATION_ERROR', '目前狀態與政策尚未允許此操作。')
  if (!Number.isSafeInteger(detail.exception.version) || detail.exception.version < 0 || detail.exception.version >= Number.MAX_SAFE_INTEGER) throw new ExceptionApiError(400, 'CLIENT_VALIDATION_ERROR', '異常版本無法再遞增，請重新讀取並確認。')
  if (![...reason].length || [...reason].length > 1000 || !nonblankExceptionText(reason)) throw new ExceptionApiError(400, 'CLIENT_VALIDATION_ERROR', '請填寫 1–1000 字元的非空白原因；此內容將供司機查看。', { fields: { reason: '請填寫有效原因，原文不會被自動修剪或正規化。' } })
  return freezeExceptionCommand({ kind: mode, exceptionId: detail.exception.id, body: { expectedVersion: detail.exception.version, reason }, original: detail.exception })
}
