import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrderEditor from './OrderEditor'
import { TomApiError, checkOrder, createOrder, getOrder, newIdempotencyKey, replaceOrder } from './tomApi'
import type { Order, OrderDraft } from './tomApi'
import { getCustomerOptions } from './crmApi'
import { editorFromOrder, newItem, newTask, toOrderDraft, validWireDate, wireInstant } from './orderEditorModel'

vi.mock('./tomApi', async (original) => ({ ...await original<typeof import('./tomApi')>(), checkOrder: vi.fn(), createOrder: vi.fn(), getOrder: vi.fn(), newIdempotencyKey: vi.fn(), replaceOrder: vi.fn() }))
vi.mock('./crmApi', () => ({ getCustomerOptions: vi.fn() }))
const id = '00000000-0000-4000-8000-000000000001'
const customerId = '00000000-0000-4000-8000-000000000101'
const itemId = '00000000-0000-4000-8000-000000000201'
const taskId = '00000000-0000-4000-8000-000000000301'
function saved(patch: Partial<Order> = {}): Order {
  return { id, orderNo: 'SYNTHETIC-ORDER', type: 'LOCAL', customerId, customerName: '歷史合成客戶', lifecycleStatus: 'OPEN', acceptanceStatus: 'DRAFT', version: 2,
    createdAt: '2026-11-25T09:00:00.123456+08:00', updatedAt: '2026-11-25T09:00:00.123456+08:00', createdBy: id, updatedBy: id, customerReference: null, remark: null, shipping: null, items: [], tasks: [], ...patch }
}
function fullOrder(patch: Partial<Order> = {}): Order {
  return saved({ shipping: { shippingCompany: null, vesselVoyage: null, pol: null, pod: null, eta: null, etd: null, blNo: null }, remark: '',
    items: [{ id: itemId, clientRef: 'item-one', sequence: 1, itemKind: 'CARGO', containerType: '自訂櫃型', containerNo: null, packageQty: 2147483647, grossWeight: '9007199254740993.123456789012345', cbm: '0', lastPickupDate: '2026-12-04', deliveryCutoffAt: '2026-12-05T06:00:00.123456+23:59' }],
    tasks: [{ id: taskId, taskNo: 'SYNTHETIC-TASK', orderId: id, itemId, clientRef: 'task-one', itemRef: 'item-one', taskKind: null, pickupName: '含\u0000原值', pickupAddress: '合成地址甲\r\n二樓', deliveryName: null, deliveryAddress: null,
      siteContact: { name: null, phone: null }, plannedStartAt: '2026-12-01T09:00:00.123456+23:59', plannedEndAt: '2026-12-01T09:00:00.123457+23:59', predecessorRefs: [], predecessorTaskIds: [], requiredTask: null }], ...patch })
}
const success = (data: Order, replayed = false) => ({ code: 'OK' as const, message: '成功', data, traceId: 'synthetic-trace', replayed, location: `/api/v1/orders/${data.id}` })
const field = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } })
beforeEach(() => {
  vi.resetAllMocks()
  let key = 0
  vi.mocked(newIdempotencyKey).mockImplementation(() => `synthetic-key-${++key}`)
  vi.mocked(getCustomerOptions).mockResolvedValue({ content: [{ id: customerId, customerCode: 'SYNTHETIC_C', name: '合成有效客戶', version: 0 }], page: 0, size: 20, totalElements: 1, totalPages: 1 })
})
async function prepareNew() {
  await screen.findByRole('option', { name: '合成有效客戶 · SYNTHETIC_C' })
  field('訂單類型', 'LOCAL'); field('選擇客戶', customerId)
}

describe('order draft editor', () => {
  it('creates a minimal draft without requiring acceptance fields', async () => {
    const onSaved = vi.fn()
    vi.mocked(createOrder).mockResolvedValue(success(saved({ version: 0 })))
    render(<OrderEditor onSaved={onSaved} onExpired={vi.fn()} />)
    await prepareNew()
    fireEvent.click(screen.getByRole('button', { name: '建立草稿' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
    expect(createOrder).toHaveBeenCalledWith({ type: 'LOCAL', customerId, customerReference: null, remark: null, shipping: null, items: [], tasks: [] }, 'synthetic-key-1')
    expect(screen.getByRole('heading', { name: '編輯訂單 SYNTHETIC-ORDER' })).toBeVisible()
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeEnabled()
  })

  it('saves every loaded field without DOM normalization, float conversion, or server-owned fields', async () => {
    const order = fullOrder()
    vi.mocked(getCustomerOptions).mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    vi.mocked(replaceOrder).mockResolvedValue(success(fullOrder({ version: 3 })))
    render(<OrderEditor order={order} onSaved={vi.fn()} onExpired={vi.fn()} />)
    await screen.findByText('此頁沒有符合條件的有效客戶。')
    expect(screen.getByLabelText('選擇客戶')).toHaveValue(customerId)
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(replaceOrder).toHaveBeenCalledOnce())
    const call = vi.mocked(replaceOrder).mock.calls[0] as unknown as [string, number, OrderDraft, string]
    expect(call.slice(0, 2)).toEqual([id, 2])
    expect(call[2].remark).toBe('')
    expect(call[2].shipping).toEqual(order.shipping)
    expect(call[2].items?.[0]).toMatchObject({ grossWeight: '9007199254740993.123456789012345', cbm: '0', packageQty: 2147483647, deliveryCutoffAt: '2026-12-05T06:00:00.123456+23:59' })
    expect(call[2].tasks?.[0]).toMatchObject({ pickupName: '含\u0000原值', pickupAddress: '合成地址甲\r\n二樓', siteContact: { name: null, phone: null }, requiredTask: null, plannedEndAt: '2026-12-01T09:00:00.123457+23:59' })
    expect(Object.keys(call[2])).not.toContain('id')
    expect(Object.keys(call[2].items![0])).not.toContain('id')
    expect(Object.keys(call[2].tasks![0])).not.toContain('taskNo')
  })

  it('locks an uncertain create and retries the exact original body and key', async () => {
    const onDirty = vi.fn()
    vi.mocked(createOrder).mockRejectedValueOnce(new TomApiError(0, 'NETWORK_ERROR', '無法確認結果', { uncertain: true }))
      .mockResolvedValueOnce(success(saved({ version: 0 }), true))
    render(<OrderEditor onSaved={vi.fn()} onExpired={vi.fn()} onDirtyChange={onDirty} />)
    await prepareNew()
    fireEvent.click(screen.getByRole('button', { name: '建立草稿' }))
    await screen.findByRole('button', { name: '以原內容重試保存' })
    expect(screen.getByLabelText('訂單類型')).toBeDisabled()
    expect(screen.getByRole('button', { name: '建立草稿' })).toBeDisabled()
    expect(onDirty).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: '以原內容重試保存' }))
    await screen.findByText(/已取回這次保存的第 0 版原結果/)
    const calls = vi.mocked(createOrder).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[1][0]).toBe(calls[0][0])
    expect(calls[1][1]).toBe(calls[0][1])
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('cannot discard an uncertain PUT through a comparison panel opened before the request', async () => {
    const order = saved()
    vi.mocked(getOrder).mockResolvedValue(success(saved({ version: 8, customerReference: '遠端內容' })))
    vi.mocked(replaceOrder).mockRejectedValueOnce(new TomApiError(500, 'INTERNAL_ERROR', '結果未確認', { uncertain: true })).mockResolvedValueOnce(success(saved({ version: 3, customerReference: '本機內容' }), true))
    render(<OrderEditor order={order} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '讀取伺服器版本比較' }))
    await screen.findByRole('button', { name: '捨棄本機內容，載入伺服器版本' })
    field('客戶參考號', '本機內容')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByRole('button', { name: '以原內容重試保存' })
    const staleDiscard = screen.queryByRole('button', { name: '捨棄本機內容，載入伺服器版本' })
    if (staleDiscard) expect(staleDiscard).toBeDisabled()
    expect(screen.queryByRole('button', { name: '確認載入伺服器版本' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('本機內容')
    expect(screen.getByRole('button', { name: '讀取伺服器版本比較' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '以原內容重試保存' }))
    await screen.findByText(/已取回這次保存的第 3 版原結果/)
    const calls = vi.mocked(replaceOrder).mock.calls as unknown as [string, number, OrderDraft, string][]
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual(calls[0])
    expect(calls[1][2].customerReference).toBe('本機內容')
    expect(calls[1][1]).toBe(2)
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('preserves local version-conflict edits until an explicit two-step reload', async () => {
    vi.mocked(replaceOrder).mockRejectedValueOnce(new TomApiError(409, 'VERSION_CONFLICT', '版本已更新', { actualVersion: 4 }))
    vi.mocked(getOrder).mockResolvedValue(success(saved({ version: 5, customerReference: '遠端第5版' })))
    render(<OrderEditor order={saved()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    field('客戶參考號', '本機未保存')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByRole('heading', { name: '版本衝突，本機草稿已保留' })
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('本機未保存')
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeDisabled()
    expect(replaceOrder).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '讀取伺服器版本比較' }))
    await screen.findByText(/已讀取伺服器第 5 版/)
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('本機未保存')
    fireEvent.click(screen.getByRole('button', { name: '捨棄本機內容，載入伺服器版本' }))
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('本機未保存')
    fireEvent.click(screen.getByRole('button', { name: '確認載入伺服器版本' }))
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('遠端第5版')
    expect(screen.getByText('已保存版本 5')).toBeVisible()
  })

  it('does not clear shipping or item values when changing the order type', async () => {
    render(<OrderEditor order={fullOrder({ type: 'IMPORT', shipping: { shippingCompany: '保留船公司', vesselVoyage: '原航次', pol: '港甲', pod: '港乙', eta: '2026-12-01T09:00:00.123456+08:00', etd: null, blNo: '原提單' } })} onSaved={vi.fn()} onExpired={vi.fn()} />)
    field('訂單類型', 'LOCAL')
    expect(screen.getByLabelText('船公司')).toHaveValue('保留船公司')
    expect(screen.getByLabelText('預計到港時間')).toHaveValue('2026-12-01T09:00:00.123456+08:00')
    expect(screen.getByLabelText('毛重 kg')).toHaveValue('9007199254740993.123456789012345')
    fireEvent.click(screen.getByRole('button', { name: '移除整組船務' }))
    expect(screen.getByLabelText('船公司')).toHaveValue('保留船公司')
    fireEvent.click(screen.getByRole('button', { name: '確認移除船務資料' }))
    expect(screen.queryByLabelText('船公司')).not.toBeInTheDocument()
  })

  it('checks only a saved version and prevents wrong field jumps after local edits', async () => {
    vi.mocked(checkOrder).mockResolvedValue({ code: 'OK', message: '成功', traceId: 'check-trace', data: { orderId: id, version: 2, checkedRules: ['ORD-P01', 'ORD-P02', 'ORD-P03'], confirmedRulesOutcome: 'INCOMPLETE', missingFields: [{ path: '/items/0/containerType', fieldId: 'IF04', ruleId: 'ORD-P02', message: '請補櫃型' }], pendingPolicyIds: ['D04_DICTIONARY'], operationalAcceptance: 'NOT_GRANTED' } })
    render(<OrderEditor order={fullOrder()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '檢查已保存版本' }))
    const jump = await screen.findByRole('button', { name: /請補櫃型/ })
    expect(jump).toBeEnabled()
    expect(screen.getByText(/尚未授予正式受理/)).toBeVisible()
    field('櫃型', '新本機櫃型')
    expect(jump).toBeDisabled()
    expect(screen.getByText(/先保存／重新載入再定位欄位/)).toBeVisible()
    expect(screen.getByText('D04_DICTIONARY')).toBeVisible()
    expect(createOrder).not.toHaveBeenCalled()
    expect(replaceOrder).not.toHaveBeenCalled()
  })

  it('reorders stable item references and refuses to silently remove linked tasks', async () => {
    const one = fullOrder()
    const order = fullOrder({ items: [...one.items, { ...one.items[0], id: '00000000-0000-4000-8000-000000000202', clientRef: 'item-two', sequence: 2 }] })
    vi.mocked(replaceOrder).mockResolvedValue(success({ ...order, version: 3 }))
    render(<OrderEditor order={order} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '上移項目 2' }))
    expect(within(screen.getByRole('group', { name: '項目 1' })).getByLabelText('項目參照')).toHaveValue('item-two')
    expect(screen.getByLabelText('所屬項目')).toHaveValue('item-one')
    fireEvent.click(screen.getByRole('button', { name: '移除項目 2' }))
    expect(screen.getByText(/此項目仍有 1 個任務/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '確認移除項目' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(replaceOrder).toHaveBeenCalledOnce())
    const body = (vi.mocked(replaceOrder).mock.calls[0] as unknown as [string, number, OrderDraft])[2]
    expect(body.items?.map((item) => item.clientRef)).toEqual(['item-two', 'item-one'])
    expect(body.tasks?.[0].itemRef).toBe('item-one')
  })

  it('exits through the existing authentication flow on 401 and does not expose stale data through a retry', async () => {
    const expired = vi.fn()
    vi.mocked(replaceOrder).mockRejectedValue(new TomApiError(401, 'UNAUTHENTICATED', '請重新登入'))
    render(<OrderEditor order={saved()} onSaved={vi.fn()} onExpired={expired} />)
    field('客戶參考號', '本機內容')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(expired).toHaveBeenCalledOnce())
    expect(screen.queryByRole('button', { name: '以原內容重試保存' })).not.toBeInTheDocument()
  })

  it('links structured server field errors to the editable field and its keyboard focus', async () => {
    vi.mocked(replaceOrder).mockRejectedValue(new TomApiError(400, 'VALIDATION_ERROR', '草稿格式不符', { traceId: 'synthetic-validation-trace', fieldErrors: [{ path: '/items/0/grossWeight', fieldId: 'IF07', ruleId: 'ORD-FORMAT', message: '合成重量格式錯誤' }, { path: '/tasks/0/clientRef', fieldId: 'TF01', ruleId: 'FORMAT', message: '合成任務識別錯誤' }] }))
    render(<OrderEditor order={fullOrder()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    const jump = await screen.findByRole('button', { name: /合成重量格式錯誤/ })
    const weight = screen.getByLabelText('毛重 kg')
    expect(weight).toHaveAttribute('aria-invalid', 'true')
    expect(weight).toHaveAccessibleDescription(/合成重量格式錯誤/)
    fireEvent.click(jump)
    expect(weight).toHaveFocus()
    const ref = screen.getByLabelText('任務參照')
    expect(ref.closest('details')).not.toHaveAttribute('open')
    fireEvent.click(screen.getByRole('button', { name: /合成任務識別錯誤/ }))
    expect(ref.closest('details')).toHaveAttribute('open')
    expect(ref).toHaveFocus()
    expect(screen.getByText(/synthetic-validation-trace/)).toBeVisible()
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '以原內容重試保存' })).not.toBeInTheDocument()
  })

  it('focuses the nearest existing group for missing nested data without changing the draft', async () => {
    vi.mocked(checkOrder).mockResolvedValue({ code: 'OK', message: '成功', traceId: 'check-trace', data: { orderId: id, version: 2, checkedRules: ['ORD-P01', 'ORD-P02', 'ORD-P03'], confirmedRulesOutcome: 'INCOMPLETE', missingFields: [
      { path: '/shipping/blNo', fieldId: 'SF07', ruleId: 'ORD-P01', message: '補齊提單號' },
      { path: '/items/0/requiredTask', fieldId: 'IF01', ruleId: 'ORD-P02', message: '此項目需必要任務' },
    ], pendingPolicyIds: [], operationalAcceptance: 'NOT_GRANTED' } })
    render(<OrderEditor order={fullOrder({ type: 'IMPORT', shipping: null })} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '檢查已保存版本' }))
    fireEvent.click(await screen.findByRole('button', { name: /補齊提單號/ }))
    expect(screen.getByRole('region', { name: '船務資料' })).toHaveFocus()
    expect(screen.getByRole('button', { name: '加入船務資料' })).toBeVisible()
    expect(screen.queryByLabelText('提單號')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /此項目需必要任務/ }))
    expect(screen.getByRole('group', { name: '項目 1' })).toHaveFocus()
    expect(screen.getByText('目前內容對應已保存版本 2。')).toBeVisible()
    expect(createOrder).not.toHaveBeenCalled()
    expect(replaceOrder).not.toHaveBeenCalled()
  })

  it('leaves CSRF failures editable without silently retrying a rejected write', async () => {
    const expired = vi.fn()
    vi.mocked(replaceOrder).mockRejectedValueOnce(new TomApiError(403, 'CSRF_INVALID', '安全驗證已更新，請再保存')).mockResolvedValueOnce(success(saved({ version: 3, customerReference: '明確再次保存' })))
    render(<OrderEditor order={saved()} onSaved={vi.fn()} onExpired={expired} />)
    field('客戶參考號', '第一次本機內容')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('安全驗證已更新，請再保存')
    expect(replaceOrder).toHaveBeenCalledOnce()
    expect(expired).not.toHaveBeenCalled()
    expect(screen.getByLabelText('客戶參考號')).toBeEnabled()
    expect(screen.queryByRole('button', { name: '以原內容重試保存' })).not.toBeInTheDocument()
    field('客戶參考號', '明確再次保存')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('草稿已保存，版本 3。')
    expect(replaceOrder).toHaveBeenCalledTimes(2)
    expect(newIdempotencyKey).toHaveBeenCalledTimes(2)
  })

  it('retains the original write identity while an idempotent request is still in progress', async () => {
    vi.mocked(replaceOrder).mockRejectedValueOnce(new TomApiError(409, 'IDEMPOTENCY_IN_PROGRESS', '先前保存仍處理中')).mockResolvedValueOnce(success(saved({ version: 3 }), true))
    render(<OrderEditor order={saved()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByRole('button', { name: '以原內容重試保存' })
    expect(screen.getByLabelText('客戶參考號')).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '以原內容重試保存' }))
    await screen.findByText(/已取回這次保存的第 3 版原結果/)
    expect(vi.mocked(replaceOrder).mock.calls[1]).toEqual(vi.mocked(replaceOrder).mock.calls[0])
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it.each(['create', 'replace'] as const)('keeps an unresolved %s frozen when a later retry is rejected before execution', async (mode) => {
    const lostResponse = new TomApiError(0, 'NETWORK_ERROR', '先前保存結果未知', { uncertain: true })
    const rejectedRetry = new TomApiError(403, 'CSRF_INVALID', '本次安全驗證失敗，未重新執行')
    if (mode === 'create') vi.mocked(createOrder).mockRejectedValueOnce(lostResponse).mockRejectedValueOnce(rejectedRetry).mockResolvedValueOnce(success(saved({ version: 0 }), true))
    else vi.mocked(replaceOrder).mockRejectedValueOnce(lostResponse).mockRejectedValueOnce(rejectedRetry).mockResolvedValueOnce(success(saved({ version: 3 }), true))
    render(<OrderEditor order={mode === 'replace' ? saved() : undefined} onSaved={vi.fn()} onExpired={vi.fn()} />)
    if (mode === 'create') await prepareNew()
    field('客戶參考號', '未確定結果的原內容')
    fireEvent.click(screen.getByRole('button', { name: mode === 'create' ? '建立草稿' : '保存草稿' }))
    fireEvent.click(await screen.findByRole('button', { name: '以原內容重試保存' }))
    await screen.findByText('本次安全驗證失敗，未重新執行')
    expect(screen.getByLabelText('客戶參考號')).toBeDisabled()
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('未確定結果的原內容')
    expect(screen.getByRole('button', { name: mode === 'create' ? '建立草稿' : '保存草稿' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '以原內容重試保存' }))
    await screen.findByText(/已取回這次保存的第/)
    const calls = mode === 'create' ? vi.mocked(createOrder).mock.calls : vi.mocked(replaceOrder).mock.calls
    expect(calls).toHaveLength(3)
    expect(calls[1]).toEqual(calls[0])
    expect(calls[2]).toEqual(calls[0])
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('allows explicit conflict recovery when a retry reaches the post-idempotency version check', async () => {
    vi.mocked(replaceOrder).mockRejectedValueOnce(new TomApiError(0, 'NETWORK_ERROR', '結果未知', { uncertain: true }))
      .mockRejectedValueOnce(new TomApiError(409, 'VERSION_CONFLICT', '已確認版本衝突', { actualVersion: 4 }))
    vi.mocked(getOrder).mockResolvedValue(success(saved({ version: 4, customerReference: '已提交的遠端資料' })))
    render(<OrderEditor order={saved()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    field('客戶參考號', '仍保留本機內容')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    fireEvent.click(await screen.findByRole('button', { name: '以原內容重試保存' }))
    await screen.findByRole('heading', { name: '版本衝突，本機草稿已保留' })
    expect(screen.queryByRole('button', { name: '以原內容重試保存' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('仍保留本機內容')
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '讀取伺服器版本比較' }))
    fireEvent.click(await screen.findByRole('button', { name: '捨棄本機內容，載入伺服器版本' }))
    fireEvent.click(screen.getByRole('button', { name: '確認載入伺服器版本' }))
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('已提交的遠端資料')
    expect(vi.mocked(replaceOrder).mock.calls[1]).toEqual(vi.mocked(replaceOrder).mock.calls[0])
    expect(newIdempotencyKey).toHaveBeenCalledOnce()
  })

  it('requires explicit review before rebasing local conflict content onto the fetched version', async () => {
    vi.mocked(replaceOrder).mockRejectedValueOnce(new TomApiError(409, 'VERSION_CONFLICT', '版本已更新', { actualVersion: 4 })).mockResolvedValueOnce(success(saved({ version: 6, customerReference: '保留本機修改' })))
    vi.mocked(getOrder).mockResolvedValue(success(saved({ version: 5, customerReference: '遠端修改' })))
    render(<OrderEditor order={saved()} onSaved={vi.fn()} onExpired={vi.fn()} />)
    field('客戶參考號', '保留本機修改')
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByRole('heading', { name: '版本衝突，本機草稿已保留' })
    fireEvent.click(screen.getByRole('button', { name: '讀取伺服器版本比較' }))
    await screen.findByRole('button', { name: '保留本機草稿，使用此版本繼續' })
    fireEvent.click(screen.getByRole('button', { name: '保留本機草稿，使用此版本繼續' }))
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '確認保留本機內容繼續' }))
    expect(screen.getByLabelText('客戶參考號')).toHaveValue('保留本機修改')
    expect(replaceOrder).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('草稿已保存，版本 6。')
    const second = vi.mocked(replaceOrder).mock.calls[1] as unknown as [string, number, OrderDraft, string]
    expect(second[1]).toBe(5)
    expect(second[2].customerReference).toBe('保留本機修改')
  })

  it('keeps noneditable saved orders read only while allowing a saved-version check', () => {
    render(<OrderEditor order={saved({ lifecycleStatus: 'CANCELLED' })} onSaved={vi.fn()} onExpired={vi.fn()} />)
    expect(screen.getByLabelText('訂單類型')).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '新增項目' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '新增任務' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '檢查已保存版本' })).toBeEnabled()
    expect(createOrder).not.toHaveBeenCalled()
    expect(replaceOrder).not.toHaveBeenCalled()
  })
})

describe('lossless order editor model', () => {
  it('keeps large decimal strings, known zero, nullable objects, and an explicitly empty remark', () => {
    const data = toOrderDraft(editorFromOrder(fullOrder()))
    expect(data.items![0].grossWeight).toBe('9007199254740993.123456789012345')
    expect(data.items![0].cbm).toBe('0')
    expect(data.items![0].containerNo).toBeNull()
    expect(data.remark).toBe('')
    expect(data.shipping).not.toBeNull()
    expect(data.tasks![0].siteContact).toEqual({ name: null, phone: null })
    expect(data.tasks![0].requiredTask).toBeNull()
  })
  it('validates calendar dates, all contract offsets, microseconds, and real instant ordering', () => {
    expect(validWireDate('2028-02-29')).toBe(true)
    expect(validWireDate('2026-02-29')).toBe(false)
    expect(wireInstant('2026-12-01T09:00:00.123456+23:59')).not.toBeNull()
    expect(wireInstant('2026-12-01T09:00:00.1234567+08:00')).toBeNull()
    expect(wireInstant('2026-12-01T09:00:00+24:00')).toBeNull()
    const model = editorFromOrder(fullOrder())
    model.tasks[0].plannedStartAt = '2026-12-01T09:00:00.123456+08:00'
    model.tasks[0].plannedEndAt = '2026-12-01T01:00:00.123455Z'
    expect(() => toOrderDraft(model)).toThrow('請修正草稿格式或關聯')
  })
  it('rejects cycles, duplicate sequences, and noninteger values without fabricating zero', () => {
    const model = editorFromOrder(fullOrder())
    model.items[0].packageQty = ''
    expect(toOrderDraft(model).items![0].packageQty).toBeNull()
    model.items[0].packageQty = '1.0000000000000000001'
    expect(() => toOrderDraft(model)).toThrow()
    model.items[0].packageQty = '1.0'
    expect(toOrderDraft(model).items![0].packageQty).toBe(1)
    const task = newTask(model.items[0].clientRef)
    model.tasks[0].predecessorRefs = [task.clientRef]
    task.predecessorRefs = [model.tasks[0].clientRef]
    model.tasks.push(task)
    expect(() => toOrderDraft(model)).toThrow()
    const item = newItem(); item.sequence = '1'; model.items.push(item)
    expect(() => toOrderDraft(model)).toThrow()
  })
  it('preserves Unicode and special text, rejecting an unpaired surrogate explicitly', () => {
    const model = editorFromOrder(saved({ customerReference: '  e\u0301\u0000  ', remark: '\r\n\u0000' }))
    expect(toOrderDraft(model).customerReference).toBe('  e\u0301\u0000  ')
    expect(toOrderDraft(model).remark).toBe('\r\n\u0000')
    model.remark = '\uD800'
    expect(() => toOrderDraft(model)).toThrow()
  })

  it('rejects trailing line breaks in typed numeric, date, time and reference fields', () => {
    expect(validWireDate('2026-12-01\n')).toBe(false)
    expect(wireInstant('2026-12-01T09:00:00+08:00\n')).toBeNull()
    const edits = [
      (model: ReturnType<typeof editorFromOrder>) => { model.items[0].grossWeight = '1.5\n' },
      (model: ReturnType<typeof editorFromOrder>) => { model.items[0].packageQty = '2\n' },
      (model: ReturnType<typeof editorFromOrder>) => { model.items[0].clientRef = 'item-one\n' },
      (model: ReturnType<typeof editorFromOrder>) => { model.customerId += '\n' },
    ]
    for (const edit of edits) { const model = editorFromOrder(fullOrder()); edit(model); expect(() => toOrderDraft(model)).toThrow() }
  })

  it('counts Unicode code points rather than cutting valid supplementary characters in half', () => {
    const model = editorFromOrder(saved())
    model.customerReference = '🚛'.repeat(200)
    model.remark = '🚛'.repeat(4000)
    expect(toOrderDraft(model).customerReference).toBe(model.customerReference)
    expect(toOrderDraft(model).remark).toBe(model.remark)
    model.customerReference += '🚛'
    expect(() => toOrderDraft(model)).toThrow()
  })
})
