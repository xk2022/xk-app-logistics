import { TomApiError } from './tomApi'
import type { OrderSummary } from './tomApi'

export function isEditableOrder(order: Pick<OrderSummary, 'lifecycleStatus' | 'acceptanceStatus'>): boolean { return order.lifecycleStatus === 'OPEN' && order.acceptanceStatus === 'DRAFT' }
export function isWaitingForDispatch(order: Pick<OrderSummary, 'lifecycleStatus' | 'acceptanceStatus'>): boolean { return order.lifecycleStatus === 'OPEN' && order.acceptanceStatus === 'ACCEPTED' }

// The acceptance service emits these domain errors only after same-key replay misses.
// Protocol/shape errors run earlier and cannot settle a previously lost response.
export function settlesPriorAcceptance(error: unknown): boolean {
  if (!(error instanceof TomApiError)) return false
  if (error.status === 400 && error.code === 'INVALID_CUSTOMER') return true
  if (error.status === 409 && ['VERSION_CONFLICT', 'ORDER_NOT_EDITABLE', 'PROTECTED_RELATION'].includes(error.code)) return true
  return error.status === 400 && error.code === 'VALIDATION_ERROR' && error.fieldErrors.length > 0 && error.fieldErrors.every((issue) =>
    /^\/(type|customerId|items|tasks|shipping)(?:\/|$)/.test(issue.path) && issue.fieldId.trim() !== '' && !issue.fieldId.trim().toLowerCase().startsWith('request') && issue.ruleId.trim() !== '' && !issue.ruleId.trim().toUpperCase().startsWith('FORMAT'))
}
