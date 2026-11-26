import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DispatchCommandForm from './DispatchCommandForm'
import DispatchManager from './DispatchManager'
import DispatchResourcePicker from './DispatchResourcePicker'
import { TmsApiError, assignTask, getTaskDispatch, getTmsResources, getTmsTasks, reassignDispatch, revokeDispatch } from './tmsApi'
import type { Assignment, DispatchDetail, ResourceCandidate, ResourcePosition, TaskSummary, TmsPage, TmsWriteResult } from './tmsApi'

vi.mock('./tmsApi', async (original) => ({ ...await original<typeof import('./tmsApi')>(), assignTask: vi.fn(), revokeDispatch: vi.fn(), reassignDispatch: vi.fn(), getTaskDispatch: vi.fn(), getTmsResources: vi.fn(), getTmsTasks: vi.fn() }))
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const time = '2026-11-26T11:00:00+08:00'
function task(patch: Partial<TaskSummary> = {}): TaskSummary { return { taskId: id(1), taskNo: 'TASK-SYNTHETIC', orderId: id(2), orderNo: 'ORDER-SYNTHETIC', orderType: 'LOCAL', orderVersion: 3, generation: 0, taskKind: '合成運送', pickupName: '合成起點', deliveryName: '合成終點', prerequisiteReady: true, currentAssignmentId: null, policyMode: 'SYNTHETIC_TEST_ONLY', blockingReasons: [], ...patch } }
function page<T>(content: T[], patch: Partial<TmsPage<T>> = {}): TmsPage<T> { return { content, page: 0, size: 5, totalElements: content.length, totalPages: content.length ? 1 : 0, ...patch } }
function assignment(patch: Partial<Assignment> = {}): Assignment { return { id: id(10), taskId: id(1), orderId: id(2), status: 'ASSIGNED', version: 0, assignedGeneration: 1, endedGeneration: null, driver: { id: id(20), driverCode: 'D1001', name: '合成司機', version: 4 }, mainVehicle: { id: id(21), plateNo: 'SYNTHETIC-A', vehicleType: id(30), vehicleTypeCode: 'SYNTHETIC_MAIN', vehicleTypeLabel: '合成主車', version: 5 }, trailer: null,
    source: { taskId: id(1), taskNo: 'TASK-SYNTHETIC', orderId: id(2), orderNo: 'ORDER-SYNTHETIC', orderType: 'LOCAL', orderVersion: 3, item: { itemKind: 'CARGO', containerType: null, containerNo: null, packageQty: 1, grossWeight: '0.00000000000000001', cbm: null }, taskKind: '原始 e\u0301', pickupName: '合成起點', pickupAddress: ' 原始\u0000地址\r\n ', deliveryName: '合成終點', deliveryAddress: null, siteContact: { name: '', phone: null }, plannedStartAt: '1500-01-01T01:02:03.123456+23:59', plannedEndAt: null, requiredTask: null }, policyMode: 'SYNTHETIC_TEST_ONLY', assignedBy: id(40), assignedAt: time, assignmentReason: null, endedBy: null, endedAt: null, endReason: null, previousAssignmentId: null,
    reservations: [{ id: id(50), kind: 'DRIVER', position: 'DRIVER', resourceId: id(20), acquiredAt: time, releasedAt: null, releaseReason: null }, { id: id(51), kind: 'VEHICLE', position: 'MAIN', resourceId: id(21), acquiredAt: time, releasedAt: null, releaseReason: null }], events: [{ id: id(60), action: 'ASSIGNED', assignmentId: id(10), previousAssignmentId: null, fromGeneration: 0, toGeneration: 1, actorId: id(40), occurredAt: time, reason: null }], ...patch } }
function detail(current: Assignment | null = null, patch: Partial<DispatchDetail> = {}): DispatchDetail { return { task: task(current ? { generation: current.assignedGeneration, currentAssignmentId: current.id, blockingReasons: ['ALREADY_ASSIGNED'] } : {}), current, history: page(current ? [current] : []), ...patch } }
function candidate(position: ResourcePosition, patch: Partial<ResourceCandidate> = {}): ResourceCandidate { return { id: id(position === 'DRIVER' ? 20 : position === 'MAIN' ? 21 : 22), kind: position === 'DRIVER' ? 'DRIVER' : 'VEHICLE', position, code: position === 'DRIVER' ? 'D1001' : position === 'MAIN' ? 'SYNTHETIC-A' : 'SYNTHETIC-B', label: '合成來源', version: 4, administrativelyAvailable: true, occupied: false, occupation: null, policyMode: 'SYNTHETIC_TEST_ONLY', policyApproved: true, blockingReasons: [], ...patch } }
function receipt(replayed = false): TmsWriteResult { return { code: '0000', message: '成功', data: { taskId: id(1), generation: 1, assignment: assignment() }, timestamp: time, replayed } }
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (failure: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function formProps(fresh = detail()) { return { detail: fresh, onExpired: vi.fn(), onCompleted: vi.fn(), onDirtyChange: vi.fn(), onBusyChange: vi.fn(), onPendingChange: vi.fn() } }
async function prepareAssign() { fireEvent.click(screen.getByRole('button', { name: '指派任務' })); await screen.findByRole('option', { name: 'D1001 · 合成來源' }); fireEvent.change(screen.getByLabelText('選擇司機'), { target: { value: id(20) } }); fireEvent.change(screen.getByLabelText('選擇主車'), { target: { value: id(21) } }); fireEvent.click(screen.getByRole('button', { name: '檢視指派確認' })); return screen.getByRole('button', { name: '確認指派' }) }
async function openTask() { fireEvent.click(await screen.findByRole('button', { name: '開啟派工 TASK-SYNTHETIC' })); await screen.findByRole('heading', { name: '派工操作' }) }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(assignTask).mockReset(); vi.mocked(revokeDispatch).mockReset(); vi.mocked(reassignDispatch).mockReset(); vi.mocked(getTaskDispatch).mockReset(); vi.mocked(getTmsTasks).mockReset(); vi.mocked(getTmsResources).mockReset()
  vi.mocked(getTmsResources).mockImplementation(async ({ position }) => page([candidate(position)]))
  vi.mocked(getTmsTasks).mockResolvedValue(page([task()]))
  vi.mocked(getTaskDispatch).mockResolvedValue(detail())
  vi.mocked(assignTask).mockResolvedValue(receipt())
})

describe('explicit dispatch commands', () => {
  it('disables production assignment but permits explicit revoke despite production source blockers', async () => {
    const props = formProps(detail(null, { task: task({ policyMode: 'UNCONFIRMED', blockingReasons: ['POLICY_NOT_CONFIRMED'] }) }))
    const mounted = render(<DispatchCommandForm {...props} />)
    expect(screen.getByRole('button', { name: '指派任務' })).toBeDisabled()
    mounted.rerender(<DispatchCommandForm {...props} detail={detail(assignment(), { task: task({ generation: 8, currentAssignmentId: id(10), policyMode: 'UNCONFIRMED', prerequisiteReady: false, blockingReasons: ['SOURCE_NOT_ACCEPTED', 'POLICY_NOT_CONFIRMED', 'ADMIN_UNAVAILABLE'] }) })} />)
    expect(screen.getByRole('button', { name: '改派任務' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '撤派任務' }))
    fireEvent.change(screen.getByLabelText('撤派原因'), { target: { value: '  合成撤派 e\u0301  ' } })
    fireEvent.click(screen.getByRole('button', { name: '檢視撤派確認' }))
    expect(revokeDispatch).not.toHaveBeenCalled()
    vi.mocked(revokeDispatch).mockResolvedValue(receipt())
    fireEvent.click(screen.getByRole('button', { name: '確認撤派' }))
    await waitFor(() => expect(revokeDispatch).toHaveBeenCalledWith(id(10), { expectedGeneration: 8, reason: '  合成撤派 e\u0301  ' }, expect.any(String), { taskId: id(1) }))
  })

  it('requires a second confirmation and sends exact generation and explicit null trailer', async () => {
    const props = formProps(detail(null, { task: task({ generation: 7 }) }))
    render(<DispatchCommandForm {...props} />)
    const confirm = await prepareAssign()
    expect(assignTask).not.toHaveBeenCalled()
    expect(confirm).toHaveFocus()
    fireEvent.click(confirm)
    await waitFor(() => expect(props.onCompleted).toHaveBeenCalledWith(receipt()))
    expect(assignTask).toHaveBeenCalledExactlyOnceWith(id(1), { expectedGeneration: 7, driverId: id(20), mainVehicleId: id(21), trailerId: null }, expect.any(String))
    expect(props.onBusyChange).toHaveBeenCalledWith(true)
  })

  it.each(['first CSRF', 'unknown then CSRF'])('retains the original frozen request through %s and requires a manual retry', async (flow) => {
    const csrf = new TmsApiError(403, 'E0403', '合成CSRF失效', { reason: 'CSRF_INVALID' })
    if (flow === 'first CSRF') vi.mocked(assignTask).mockRejectedValueOnce(csrf).mockResolvedValueOnce(receipt(true))
    else vi.mocked(assignTask).mockRejectedValueOnce(new TmsApiError(0, 'NETWORK_ERROR', '合成失落', { uncertain: true })).mockRejectedValueOnce(csrf).mockResolvedValueOnce(receipt(true))
    const props = formProps(); const mounted = render(<DispatchCommandForm {...props} />)
    fireEvent.click(await prepareAssign())
    const retry = await screen.findByRole('button', { name: '以原請求重試操作' })
    const first = vi.mocked(assignTask).mock.calls[0]
    expect(screen.getByLabelText('選擇司機')).toBeDisabled()
    expect(assignTask).toHaveBeenCalledTimes(1)
    // Stale source only gates new operations, never this original pending replay.
    mounted.rerender(<DispatchCommandForm {...props} stale />)
    expect(retry).toBeEnabled()
    fireEvent.click(retry)
    if (flow !== 'first CSRF') { await screen.findByText('合成CSRF失效'); expect(assignTask).toHaveBeenCalledTimes(2); fireEvent.click(await screen.findByRole('button', { name: '以原請求重試操作' })) }
    await waitFor(() => expect(props.onCompleted).toHaveBeenCalledOnce())
    for (const call of vi.mocked(assignTask).mock.calls) { expect(call).toEqual(first); expect(Object.isFrozen(call[1])).toBe(true) }
    expect(props.onExpired).not.toHaveBeenCalled()
  })

  it('keeps prior unknown frozen after shape failure and stops retry for key reuse', async () => {
    vi.mocked(assignTask).mockRejectedValueOnce(new TmsApiError(0, 'NETWORK_ERROR', '合成未知', { uncertain: true })).mockRejectedValueOnce(new TmsApiError(400, 'E0400', '合成格式拒絕', { reason: 'VALIDATION_ERROR' })).mockRejectedValueOnce(new TmsApiError(409, 'E1607', '合成重用', { reason: 'IDEMPOTENCY_KEY_REUSED' }))
    render(<DispatchCommandForm {...formProps()} />)
    fireEvent.click(await prepareAssign())
    fireEvent.click(await screen.findByRole('button', { name: '以原請求重試操作' }))
    await screen.findByText('合成格式拒絕')
    expect(screen.getByLabelText('選擇司機')).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '以原請求重試操作' }))
    await screen.findByRole('heading', { name: '重送識別不一致，停止重送' })
    expect(screen.queryByRole('button', { name: '以原請求重試操作' })).not.toBeInTheDocument()
    expect(assignTask).toHaveBeenCalledTimes(3)
  })

  it('retains conflict reason, never adopts actual generation, and cancellation cannot bypass the stale gate', async () => {
    vi.mocked(revokeDispatch).mockRejectedValue(new TmsApiError(409, 'E1601', '合成版本衝突', { reason: 'VERSION_CONFLICT', actualGeneration: 99 }))
    render(<DispatchCommandForm {...formProps(detail(assignment()))} />)
    fireEvent.click(screen.getByRole('button', { name: '撤派任務' })); fireEvent.change(screen.getByLabelText('撤派原因'), { target: { value: '保留本機合成原因' } }); fireEvent.click(screen.getByRole('button', { name: '檢視撤派確認' })); fireEvent.click(screen.getByRole('button', { name: '確認撤派' }))
    await screen.findByRole('heading', { name: '派工版本或狀態已變更，本機內容已保留' })
    expect(screen.getByLabelText('撤派原因')).toHaveValue('保留本機合成原因')
    expect(screen.getByRole('button', { name: '檢視撤派確認' })).toBeDisabled()
    expect(vi.mocked(revokeDispatch).mock.calls[0][1].expectedGeneration).toBe(1)
    fireEvent.click(screen.getByRole('button', { name: '取消派工編輯' })); fireEvent.click(screen.getByRole('button', { name: '捨棄本機操作' }))
    expect(screen.getByRole('button', { name: '撤派任務' })).toBeDisabled()
    expect(revokeDispatch).toHaveBeenCalledTimes(1)
  })

  it.each([new TmsApiError(401, 'E0401', '合成失效'), new TmsApiError(403, 'E0403', '合成撤權', { reason: 'FORBIDDEN' }), new TmsApiError(403, 'E0403', '合成未知拒絕')])('safely exits protected editing for $status/$reason', async (failure) => {
    const props = formProps(); vi.mocked(assignTask).mockRejectedValue(failure)
    render(<DispatchCommandForm {...props} />); fireEvent.click(await prepareAssign())
    await waitFor(() => expect(props.onExpired).toHaveBeenCalledOnce())
    expect(screen.queryByRole('button', { name: '以原請求重試操作' })).not.toBeInTheDocument()
  })
})

describe('server candidates and source reads', () => {
  it('retains blocked rows/counts, prevents choosing occupied resources, and scopes current reuse', async () => {
    const own = candidate('DRIVER', { occupied: true, occupation: { assignmentId: id(10), taskId: id(1) } })
    const blocked = candidate('DRIVER', { id: id(90), code: 'D-BLOCKED', occupied: true, occupation: { assignmentId: id(91), taskId: id(92) }, blockingReasons: ['RESOURCE_OCCUPIED'] })
    vi.mocked(getTmsResources).mockResolvedValue(page([own, blocked], { totalElements: 17, totalPages: 4 }))
    const select = vi.fn(), expired = vi.fn(); const mounted = render(<DispatchResourcePicker taskId={id(1)} assignmentId={id(10)} position="DRIVER" value="" disabled={false} onSelect={select} onExpired={expired} />)
    expect(await screen.findByRole('option', { name: /D1001/ })).toBeEnabled()
    expect(screen.getByRole('option', { name: /D-BLOCKED/ })).toBeDisabled()
    expect(screen.getByText('共 17 筆 · 第 1 頁')).toBeVisible()
    fireEvent.change(screen.getByLabelText('選擇司機'), { target: { value: id(90) } })
    expect(select).not.toHaveBeenCalled()
    mounted.rerender(<DispatchResourcePicker taskId={id(1)} position="DRIVER" value="" disabled={false} onSelect={select} onExpired={expired} />)
    await waitFor(() => expect(screen.getByRole('option', { name: /D1001/ })).toBeDisabled())
  })

  it('treats candidate Enter as a literal query rather than a command form submission', async () => {
    const submitted = vi.fn(), expired = vi.fn(); render(<form onSubmit={(event) => { event.preventDefault(); submitted() }}><DispatchResourcePicker taskId={id(1)} position="MAIN" value="" disabled={false} onSelect={vi.fn()} onExpired={expired} /></form>)
    await screen.findByRole('option', { name: /SYNTHETIC-A/ })
    const search = screen.getByLabelText('搜尋主車'); fireEvent.change(search, { target: { value: ' %_\\ ' } }); fireEvent.keyDown(search, { key: 'Enter' })
    await waitFor(() => expect(getTmsResources).toHaveBeenLastCalledWith({ taskId: id(1), position: 'MAIN', page: 0, size: 5, q: '%_\\' }, expect.any(AbortSignal)))
    expect(submitted).not.toHaveBeenCalled()
  })

  it('ignores late resources from a previous task context', async () => {
    const old = deferred<TmsPage<ResourceCandidate>>()
    vi.mocked(getTmsResources).mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([candidate('DRIVER', { code: 'D-NEW' })]))
    const expired = vi.fn(), select = vi.fn(); const mounted = render(<DispatchResourcePicker taskId={id(1)} position="DRIVER" value="" disabled={false} onSelect={select} onExpired={expired} />)
    const oldSignal = vi.mocked(getTmsResources).mock.calls[0][1]
    mounted.rerender(<DispatchResourcePicker taskId={id(99)} position="DRIVER" value="" disabled={false} onSelect={select} onExpired={expired} />)
    await screen.findByRole('option', { name: /D-NEW/ }); await act(async () => old.resolve(page([candidate('DRIVER', { code: 'D-OLD-PRIVATE' })])))
    expect(oldSignal?.aborted).toBe(true)
    expect(screen.queryByText(/D-OLD-PRIVATE/)).not.toBeInTheDocument()
  })
})

describe('workbench receipt versus fresh state', () => {
  it('uses real server query and pagination without hiding blocked tasks', async () => {
    vi.mocked(getTmsTasks).mockResolvedValue(page([task({ policyMode: 'UNCONFIRMED', blockingReasons: ['POLICY_NOT_CONFIRMED'] })], { totalElements: 6, totalPages: 2 }))
    render(<DispatchManager onExpired={vi.fn()} />)
    expect(await screen.findByText('共 6 筆任務')).toBeVisible()
    expect(screen.getByRole('button', { name: '開啟派工 TASK-SYNTHETIC' })).toBeEnabled()
    fireEvent.change(screen.getByLabelText('任務搜尋'), { target: { value: ' %_\\ ' } }); fireEvent.change(screen.getByLabelText('任務狀態篩選'), { target: { value: 'ALL' } }); fireEvent.change(screen.getByLabelText('每頁任務筆數'), { target: { value: '5' } }); fireEvent.click(screen.getByRole('button', { name: '查詢任務' }))
    await waitFor(() => expect(getTmsTasks).toHaveBeenLastCalledWith(0, { q: '%_\\', state: 'ALL', size: 5 }, expect.any(AbortSignal)))
    await screen.findByText('共 6 筆任務'); fireEvent.click(screen.getByRole('button', { name: '下一頁任務' }))
    await waitFor(() => expect(getTmsTasks).toHaveBeenLastCalledWith(1, { q: '%_\\', state: 'ALL', size: 5 }, expect.any(AbortSignal)))
  })

  it('keeps historical replay as a receipt and requires a successful explicit fresh read before new actions', async () => {
    vi.mocked(assignTask).mockResolvedValue(receipt(true)); render(<DispatchManager onExpired={vi.fn()} />); await openTask(); fireEvent.click(await prepareAssign())
    await screen.findByRole('heading', { name: '本次操作收據' })
    expect(screen.getByRole('heading', { name: '目前派工尚未重新讀取' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '撤派任務' })).not.toBeInTheDocument()
    expect(getTaskDispatch).toHaveBeenCalledTimes(1)
    vi.mocked(getTaskDispatch).mockRejectedValueOnce(new TmsApiError(0, 'NETWORK_ERROR', '合成讀取失敗'))
    fireEvent.click(screen.getByRole('button', { name: '重新讀取目前派工' })); await screen.findByText('合成讀取失敗')
    expect(screen.queryByRole('button', { name: '撤派任務' })).not.toBeInTheDocument()
    vi.mocked(getTaskDispatch).mockResolvedValueOnce(detail(assignment()))
    fireEvent.click(screen.getByRole('button', { name: '重新讀取目前派工' })); await screen.findByRole('button', { name: '撤派任務' })
    expect(screen.getByRole('heading', { name: '目前派工記錄' })).toBeVisible()
  })

  it('aborts a historical read started before an unknown command and preserves the original retry', async () => {
    const history = deferred<DispatchDetail>()
    vi.mocked(getTaskDispatch).mockResolvedValueOnce(detail()).mockReturnValueOnce(history.promise)
    vi.mocked(assignTask).mockRejectedValueOnce(new TmsApiError(0, 'NETWORK_ERROR', '合成失落回應', { uncertain: true })).mockResolvedValueOnce(receipt(true))
    render(<DispatchManager onExpired={vi.fn()} />); await openTask()
    fireEvent.click(screen.getByRole('button', { name: '重新讀取派工歷史' }))
    const historySignal = vi.mocked(getTaskDispatch).mock.calls[1][2]
    fireEvent.click(await prepareAssign())
    const retry = await screen.findByRole('button', { name: '以原請求重試操作' })
    const first = vi.mocked(assignTask).mock.calls[0]
    await act(async () => history.resolve(detail(assignment())))
    expect(historySignal?.aborted).toBe(true)
    expect(retry).toBeEnabled()
    expect(screen.getByRole('button', { name: '重新讀取目前派工' })).toBeDisabled()
    fireEvent.click(retry)
    await screen.findByRole('heading', { name: '本次操作收據' })
    expect(vi.mocked(assignTask).mock.calls[1]).toEqual(first)
    expect(screen.getByRole('heading', { name: '目前派工尚未重新讀取' })).toBeVisible()
  })

  it('keeps local conflict text through comparison until a guarded explicit adopt', async () => {
    vi.mocked(getTaskDispatch).mockResolvedValueOnce(detail(assignment())).mockResolvedValueOnce(detail(null, { task: task({ generation: 2 }) }))
    vi.mocked(revokeDispatch).mockRejectedValue(new TmsApiError(409, 'E1601', '合成衝突', { reason: 'VERSION_CONFLICT', actualGeneration: 2 }))
    render(<DispatchManager onExpired={vi.fn()} />); await openTask()
    fireEvent.click(screen.getByRole('button', { name: '撤派任務' })); fireEvent.change(screen.getByLabelText('撤派原因'), { target: { value: '合成本機原因' } }); fireEvent.click(screen.getByRole('button', { name: '檢視撤派確認' })); fireEvent.click(screen.getByRole('button', { name: '確認撤派' }))
    await screen.findByText('合成衝突'); await waitFor(() => expect(screen.getByRole('button', { name: '重新讀取目前派工' })).toBeEnabled()); fireEvent.click(screen.getByRole('button', { name: '重新讀取目前派工' })); await screen.findByRole('button', { name: '採用最新派工資料' })
    expect(screen.getByLabelText('撤派原因')).toHaveValue('合成本機原因')
    fireEvent.click(screen.getByRole('button', { name: '採用最新派工資料' }))
    await waitFor(() => expect(screen.queryByLabelText('撤派原因')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: '指派任務' })).toBeEnabled()
  })

  it('blocks an already-open local leave confirmation while an original retry is in flight', async () => {
    const response = deferred<TmsWriteResult>(); vi.mocked(assignTask).mockRejectedValueOnce(new TmsApiError(0, 'NETWORK_ERROR', '合成未知', { uncertain: true })).mockReturnValueOnce(response.promise)
    const busy = vi.fn(); render(<DispatchManager onExpired={vi.fn()} onBusyChange={busy} />); await openTask(); fireEvent.click(await prepareAssign()); await screen.findByRole('button', { name: '以原請求重試操作' })
    fireEvent.click(screen.getByRole('button', { name: '返回調度列表' })); const leave = screen.getByRole('button', { name: '捨棄本機操作並返回列表' }); expect(leave).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '以原請求重試操作' })); expect(leave).toBeDisabled(); fireEvent.click(leave)
    expect(screen.queryByRole('button', { name: '查詢任務' })).not.toBeInTheDocument()
    expect(busy).toHaveBeenLastCalledWith(true)
    await act(async () => response.resolve(receipt(true))); await screen.findByRole('heading', { name: '本次操作收據' }); await waitFor(() => expect(leave).not.toBeInTheDocument())
    expect(busy).toHaveBeenLastCalledWith(false)
  })

  it('clears pending editing via the parent safe-exit callback after role loss', async () => {
    vi.mocked(assignTask).mockRejectedValue(new TmsApiError(403, 'E0403', '合成撤權', { reason: 'FORBIDDEN' }))
    const expired = vi.fn(); const parent = render(<DispatchManager onExpired={expired} />); await openTask(); fireEvent.click(await prepareAssign())
    await waitFor(() => expect(expired).toHaveBeenCalledOnce()); parent.unmount()
    expect(screen.queryByText('合成起點')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '以原請求重試操作' })).not.toBeInTheDocument()
  })
})
