import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrderEditor from './OrderEditor'
import { acceptOrder } from './orderAcceptanceApi'
import { getCustomerOptions } from './crmApi'
import { TomApiError, checkOrder, createOrder, getOrder, newIdempotencyKey, replaceOrder } from './tomApi'
import type { FieldIssue, Order } from './tomApi'
import { isWaitingForDispatch, settlesPriorAcceptance } from './orderAcceptanceModel'

vi.mock('./orderAcceptanceApi', () => ({ acceptOrder: vi.fn() }))
vi.mock('./tomApi', async (original) => ({ ...await original<typeof import('./tomApi')>(), checkOrder: vi.fn(), createOrder: vi.fn(), getOrder: vi.fn(), newIdempotencyKey: vi.fn(), replaceOrder: vi.fn() }))
vi.mock('./crmApi', () => ({ getCustomerOptions: vi.fn() }))
const id = '00000000-0000-4000-8000-000000000001'
const customerId = '00000000-0000-4000-8000-000000000101'
const itemId = '00000000-0000-4000-8000-000000000201'
const taskId = '00000000-0000-4000-8000-000000000301'
const pendingPolicies = ['D04_CONTACT', 'D04_CONTAINER_FORMAT', 'D04_WEIGHT', 'D04_DICTIONARY', 'D04_DEADLINE_EXCEPTION', 'D05_REQUIRED_TASK_WAIVER']
function order(patch: Partial<Order> = {}): Order {
  return { id, orderNo: 'SYNTHETIC-ACCEPTANCE', type: 'LOCAL', customerId, customerName: '合成歷史客戶', lifecycleStatus: 'OPEN', acceptanceStatus: 'DRAFT', version: 2, createdAt: '2026-11-26T09:00:00.123456+08:00', updatedAt: '2026-11-26T09:00:00.123456+08:00', createdBy: id, updatedBy: id, customerReference: '  保留原值\u0000 ', remark: '', shipping: null,
    items: [{ id: itemId, clientRef: 'item-one', sequence: 1, itemKind: 'CARGO', containerType: null, containerNo: null, packageQty: 1, grossWeight: '9007199254740993.123456789012345', cbm: '0', lastPickupDate: '1500-03-01', deliveryCutoffAt: null }],
    tasks: [{ id: taskId, taskNo: 'SYNTHETIC-TASK', orderId: id, itemId, clientRef: 'task-one', itemRef: 'item-one', taskKind: '自訂合成作業', pickupName: '合成起點', pickupAddress: '合成起點\r\n二樓', deliveryName: '合成終點', deliveryAddress: '合成終點一號', siteContact: { name: null, phone: null }, plannedStartAt: '2026-12-01T09:00:00.123456+23:59', plannedEndAt: null, predecessorRefs: [], predecessorTaskIds: [], requiredTask: true }], ...patch }
}
const accepted = (patch: Partial<Order> = {}) => order({ acceptanceStatus: 'ACCEPTED', version: 3, ...patch })
const success = (data: Order, replayed = false) => ({ code: 'OK' as const, message: '成功', traceId: 'synthetic-accept-trace', data, replayed, location: null })
const field = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } })
const issue = (patch: Partial<FieldIssue> = {}): FieldIssue => ({ path: '/tasks/0/pickupName', fieldId: 'TF06', ruleId: 'ORD-P02', message: '合成起點缺漏', ...patch })
function openConfirmation() { fireEvent.click(screen.getByRole('button', { name: '正式受理訂單' })) }
function confirmAcceptance() { openConfirmation(); fireEvent.click(screen.getByRole('button', { name: '確認正式受理' })) }
beforeEach(() => {
  vi.resetAllMocks(); let key = 0
  vi.mocked(newIdempotencyKey).mockImplementation(() => `synthetic-accept-key-${++key}`)
  vi.mocked(getCustomerOptions).mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
  vi.mocked(acceptOrder).mockResolvedValue(success(accepted()))
  vi.mocked(checkOrder).mockResolvedValue({ code: 'OK', message: '成功', traceId: 'synthetic-check', data: { orderId: id, version: 2, checkedRules: ['ORD-P01', 'ORD-P02', 'ORD-P03'], confirmedRulesOutcome: 'COMPLETE', missingFields: [], pendingPolicyIds: pendingPolicies, operationalAcceptance: 'NOT_GRANTED' } })
})

describe('explicit order acceptance', () => {
  it('requires two explicit actions, confirms the saved identity and cancels without generating a key', () => {
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    openConfirmation()
    const confirmation = screen.getByRole('group', { name: '正式受理確認' })
    expect(within(confirmation).getByText('SYNTHETIC-ACCEPTANCE')).toBeVisible()
    expect(within(confirmation).getByText('合成歷史客戶')).toBeVisible()
    expect(within(confirmation).getByText('2')).toBeVisible()
    expect(within(confirmation).getByRole('button', { name: '確認正式受理' })).toHaveFocus()
    expect(within(confirmation).getByText(/成功受理的內容將不可再以草稿方式修改/)).toBeVisible()
    expect(acceptOrder).not.toHaveBeenCalled(); expect(newIdempotencyKey).not.toHaveBeenCalled()
    fireEvent.click(within(confirmation).getByRole('button', { name: '取消受理確認' }))
    expect(screen.queryByRole('group', { name: '正式受理確認' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toHaveFocus()
  })

  it('does not autoaccept from COMPLETE and does not block because six policy IDs remain pending', async () => {
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '檢查已保存版本' }))
    await screen.findByText(/已確認規則欄位齊全/)
    expect(acceptOrder).not.toHaveBeenCalled()
    for (const policy of pendingPolicies) expect(screen.getByText(policy)).toBeVisible()
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeEnabled()
    confirmAcceptance()
    await screen.findByRole('heading', { name: '已受理・待派車' })
    expect(acceptOrder).toHaveBeenCalledWith(id, { expectedVersion: 2 }, 'synthetic-accept-key-1')
    expect(createOrder).not.toHaveBeenCalled(); expect(replaceOrder).not.toHaveBeenCalled()
  })

  it('accepts without requiring a prior check and immediately adopts the complete readonly server baseline', async () => {
    const saved = vi.fn(), dirty = vi.fn(), busy = vi.fn()
    render(<OrderEditor order={order()} onSaved={saved} onExpired={vi.fn()} onDirtyChange={dirty} onBusyChange={busy} />)
    confirmAcceptance()
    await screen.findByRole('heading', { name: '檢視訂單 SYNTHETIC-ACCEPTANCE' })
    expect(saved).toHaveBeenCalledWith(accepted())
    expect(screen.getByText('已保存版本 3')).toBeVisible()
    expect(screen.getByLabelText('訂單類型')).toBeDisabled()
    expect(screen.getByLabelText('客戶參考號')).toBeDisabled()
    expect(screen.getByLabelText('毛重 kg')).toHaveValue('9007199254740993.123456789012345')
    expect(screen.getByLabelText('毛重 kg')).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '新增項目' })).toBeDisabled()
    expect(checkOrder).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /^派車|撤回|配對|結案/ })).not.toBeInTheDocument()
    await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(false))
    expect(busy).toHaveBeenLastCalledWith(false)
  })

  it('never creates event actor/time UI fields or calls FMS eligibility sources', async () => {
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    confirmAcceptance(); await screen.findByRole('heading', { name: '已受理・待派車' })
    expect(screen.queryByText('受理人員')).not.toBeInTheDocument()
    expect(screen.queryByText('受理時間')).not.toBeInTheDocument()
    expect(Object.keys(vi.mocked(acceptOrder).mock.calls[0][1])).toEqual(['expectedVersion'])
  })

  it('disables acceptance for new, locally dirty and unresolved draft operations', async () => {
    const rendered = render(<OrderEditor onSaved={vi.fn()} onExpired={vi.fn()} />)
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
    rendered.unmount()
    vi.mocked(replaceOrder).mockRejectedValue(new TomApiError(0, 'NETWORK_ERROR', '原保存結果未知', { uncertain: true }))
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    field('客戶參考號', '本機內容')
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByRole('button', { name: '以原內容重試保存' })
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
    expect(acceptOrder).not.toHaveBeenCalled()
  })

  it('closes a stale confirmation when local fields change or a draft save starts', async () => {
    vi.mocked(replaceOrder).mockResolvedValue(success(order({ version: 3 })))
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    openConfirmation(); field('客戶參考號', '本機新內容')
    expect(screen.queryByRole('button', { name: '確認正式受理' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('草稿已保存，版本 3。')
    openConfirmation()
    expect(within(screen.getByRole('group', { name: '正式受理確認' })).getByText('3')).toBeVisible()
    expect(acceptOrder).not.toHaveBeenCalled()
  })

  it.each([
    new TomApiError(403, 'CSRF_INVALID', '本次安全驗證失敗'),
    new TomApiError(0, 'NETWORK_ERROR', '取得安全驗證失敗'),
    new TomApiError(404, 'ORDER_NOT_FOUND', '本次找不到資料'),
    new TomApiError(400, 'VALIDATION_ERROR', '本次請求格式不符', { fieldErrors: [issue({ path: '/expectedVersion', fieldId: 'AF01', ruleId: 'VERSION' })] }),
    new TomApiError(400, 'VALIDATION_ERROR', '本次欄位格式不符', { fieldErrors: [issue({ path: '/tasks/0/pickupName', ruleId: 'FORMAT' })] }),
    new TomApiError(409, 'IDEMPOTENCY_IN_PROGRESS', '本次仍處理中'),
  ])('keeps a prior uncertain acceptance frozen after a retry fails with $code/$status', async (retryFailure) => {
    vi.mocked(acceptOrder).mockRejectedValueOnce(new TomApiError(0, 'NETWORK_ERROR', '受理結果未知', { uncertain: true })).mockRejectedValueOnce(retryFailure).mockResolvedValueOnce(success(accepted(), true))
    const dirty = vi.fn()
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} onDirtyChange={dirty} />)
    confirmAcceptance()
    fireEvent.click(await screen.findByRole('button', { name: '以原請求重試受理' }))
    await screen.findByText(retryFailure.message)
    expect(screen.getByLabelText('訂單類型')).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '讀取伺服器版本比較' })).toBeDisabled()
    expect(dirty).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: '以原請求重試受理' }))
    await screen.findByText(/已取回這次受理的第 3 版原結果/)
    const calls = vi.mocked(acceptOrder).mock.calls
    expect(calls).toHaveLength(3)
    expect(calls[1][1]).toBe(calls[0][1]); expect(calls[2][1]).toBe(calls[0][1])
    expect(calls[1]).toEqual(calls[0]); expect(calls[2]).toEqual(calls[0])
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('stops key-reused acceptance retries while retaining the frozen original operation', async () => {
    vi.mocked(acceptOrder).mockRejectedValue(new TomApiError(409, 'IDEMPOTENCY_KEY_REUSED', '請先確認原結果'))
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    confirmAcceptance()
    await screen.findByRole('heading', { name: '停止這次重送，先確認伺服器結果' })
    expect(screen.queryByRole('button', { name: '以原請求重試受理' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('訂單類型')).toBeDisabled()
    expect(screen.getByRole('button', { name: '讀取伺服器版本比較' })).toBeDisabled()
    expect(acceptOrder).toHaveBeenCalledOnce(); expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('settles uncertainty only after stored-aggregate completeness errors and focuses the actual field', async () => {
    vi.mocked(acceptOrder).mockRejectedValueOnce(new TomApiError(500, 'INTERNAL_ERROR', '受理結果未知', { uncertain: true }))
      .mockRejectedValueOnce(new TomApiError(400, 'VALIDATION_ERROR', '目前聚合缺漏', { fieldErrors: [issue()] }))
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    confirmAcceptance()
    fireEvent.click(await screen.findByRole('button', { name: '以原請求重試受理' }))
    const jump = await screen.findByRole('button', { name: /合成起點缺漏/ })
    expect(screen.queryByRole('button', { name: '以原請求重試受理' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('起點名稱')).toBeEnabled()
    fireEvent.click(jump)
    expect(screen.getByLabelText('起點名稱')).toHaveFocus()
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it.each(['VERSION_CONFLICT', 'ORDER_NOT_EDITABLE'] as const)('settles a definitive $0 and only loads a remote accepted record after confirmation', async (code) => {
    vi.mocked(acceptOrder).mockRejectedValueOnce(new TomApiError(0, 'NETWORK_ERROR', '受理結果未知', { uncertain: true })).mockRejectedValueOnce(new TomApiError(409, code, '遠端狀態已變更', { actualVersion: 4 }))
    vi.mocked(getOrder).mockResolvedValue(success(accepted({ customerReference: '真正遠端受理內容', version: 4 })))
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    confirmAcceptance()
    fireEvent.click(await screen.findByRole('button', { name: '以原請求重試受理' }))
    await screen.findByText('遠端狀態已變更')
    expect(screen.queryByRole('button', { name: '以原請求重試受理' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '讀取伺服器版本比較' }))
    await screen.findByRole('button', { name: '捨棄本機內容，載入伺服器版本' })
    expect(screen.queryByRole('button', { name: '保留本機草稿，使用此版本繼續' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('客戶參考號')).not.toHaveValue('真正遠端受理內容')
    fireEvent.click(screen.getByRole('button', { name: '捨棄本機內容，載入伺服器版本' }))
    fireEvent.click(screen.getByRole('button', { name: '確認載入伺服器版本' }))
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('真正遠端受理內容')
    expect(screen.getByRole('heading', { name: '已受理・待派車' })).toBeVisible()
    expect(screen.getByLabelText('客戶參考號')).toBeDisabled()
    expect(acceptOrder).toHaveBeenCalledTimes(2)
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('separates an accepted state from the unchanged NOT_GRANTED check result', async () => {
    vi.mocked(checkOrder).mockResolvedValue({ code: 'OK', message: '成功', traceId: 'synthetic-check', data: { orderId: id, version: 3, checkedRules: ['ORD-P01', 'ORD-P02', 'ORD-P03'], confirmedRulesOutcome: 'COMPLETE', missingFields: [], pendingPolicyIds: pendingPolicies, operationalAcceptance: 'NOT_GRANTED' } })
    render(<OrderEditor order={accepted()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '檢查已保存版本' }))
    await screen.findByText(/此完整性檢核不執行受理（NOT_GRANTED）/)
    expect(screen.queryByText(/尚未授予正式受理/)).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '已受理・待派車' })).toBeVisible()
    expect(acceptOrder).not.toHaveBeenCalled()
  })

  it('never claims waiting for dispatch on closed or cancelled accepted records', () => {
    render(<OrderEditor order={accepted({ lifecycleStatus: 'CLOSED' })} onSaved={vi.fn()} onExpired={vi.fn()} />)
    expect(screen.getByRole('heading', { name: '訂單已受理' })).toBeVisible()
    expect(screen.queryByText('已受理・待派車')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '正式受理訂單' })).toBeDisabled()
  })

  it('exits through authentication handling on 401 or FORBIDDEN without replaying automatically', async () => {
    const expired = vi.fn()
    vi.mocked(acceptOrder).mockRejectedValue(new TomApiError(401, 'UNAUTHENTICATED', '登入已失效'))
    render(<OrderEditor order={order()} onSaved={vi.fn()} onExpired={expired} />)
    confirmAcceptance()
    await waitFor(() => expect(expired).toHaveBeenCalledOnce())
    expect(acceptOrder).toHaveBeenCalledOnce()
  })
})

describe('acceptance replay settlement policy', () => {
  it.each(['/type', '/customerId', '/items', '/items/0/sequence', '/tasks', '/tasks/0/pickupName', '/shipping', '/shipping/blNo'])('only accepts a stored-domain completeness path %s as a definitive rejection', (path) => {
    expect(settlesPriorAcceptance(new TomApiError(400, 'VALIDATION_ERROR', '合成缺漏', { fieldErrors: [issue({ path })] }))).toBe(true)
  })
  it.each([
    issue({ path: '/expectedVersion' }), issue({ path: '/headers/Idempotency-Key' }), issue({ path: '/draft/items' }), issue({ path: '' }), issue({ path: '/itemsSuffix' }),
    issue({ fieldId: 'request' }), issue({ fieldId: ' request.headers ' }), issue({ fieldId: '' }), issue({ ruleId: 'FORMAT' }), issue({ ruleId: ' format ' }), issue({ ruleId: '' }),
  ])('does not settle a request-level or ambiguous validation error %#', (invalidIssue) => {
    expect(settlesPriorAcceptance(new TomApiError(400, 'VALIDATION_ERROR', '請求不符', { fieldErrors: [invalidIssue] }))).toBe(false)
    expect(settlesPriorAcceptance(new TomApiError(400, 'VALIDATION_ERROR', '混合錯誤', { fieldErrors: [issue(), invalidIssue] }))).toBe(false)
  })
  it('keeps empty and non-domain errors unresolved, while recognizing post-replay state/customer failures', () => {
    expect(settlesPriorAcceptance(new TomApiError(400, 'VALIDATION_ERROR', '請求不符'))).toBe(false)
    expect(settlesPriorAcceptance(new TomApiError(403, 'CSRF_INVALID', '拒絕'))).toBe(false)
    expect(settlesPriorAcceptance(new TomApiError(404, 'ORDER_NOT_FOUND', '找不到'))).toBe(false)
    expect(settlesPriorAcceptance(new TomApiError(400, 'INVALID_CUSTOMER', '目前客戶無效'))).toBe(true)
    expect(settlesPriorAcceptance(new TomApiError(409, 'PROTECTED_RELATION', '關聯受保護'))).toBe(true)
    expect(isWaitingForDispatch(accepted())).toBe(true)
    expect(isWaitingForDispatch(accepted({ lifecycleStatus: 'CANCELLED' }))).toBe(false)
  })
})
