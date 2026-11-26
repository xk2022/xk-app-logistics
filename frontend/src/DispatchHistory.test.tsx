import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DispatchHistory from './DispatchHistory'
import { getTaskDispatch, TmsApiError } from './tmsApi'
import type { Assignment, DispatchDetail, TmsPage } from './tmsApi'

vi.mock('./tmsApi', async (original) => ({ ...await original<typeof import('./tmsApi')>(), getTaskDispatch: vi.fn() }))
const readHistory = vi.mocked(getTaskDispatch)
const id = (number: number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const taskId = id(1), orderId = id(2), actorId = id(3)
const assignedAt = '2026-11-26T02:00:00.123456Z', endedAt = '2026-11-26T02:00:01.123456Z'
function assignment(generation: number, status: 'ASSIGNED' | 'REVOKED' = 'ASSIGNED'): Assignment {
  const terminal = status === 'REVOKED', assignmentId = id(1000 + generation)
  const driverId = id(2000 + generation), vehicleId = id(3000 + generation)
  return { id: assignmentId, taskId, orderId, status, version: terminal ? 1 : 0, assignedGeneration: generation,
    endedGeneration: terminal ? generation + 1 : null, driver: { id: driverId, driverCode: `SYNTHETIC-HISTORY-D${generation}`, name: `合成歷史司機 ${generation}`, version: 0 },
    mainVehicle: { id: vehicleId, plateNo: `SYN-HISTORY-${generation}`, vehicleType: id(50), vehicleTypeCode: 'SYNTHETIC_TMS_IT_MAIN', vehicleTypeLabel: '合成主車類別', version: 0 }, trailer: null,
    source: { taskId, taskNo: 'SYNTHETIC-HISTORY-TASK', orderId, orderNo: 'SYNTHETIC-HISTORY-ORDER', orderType: 'LOCAL', orderVersion: 1,
      item: { itemKind: 'CARGO', containerType: null, containerNo: null, packageQty: 1, grossWeight: '0.0000000000000000001', cbm: '0' },
      taskKind: '合成歷史作業', pickupName: `合成歷史起點 ${generation}`, pickupAddress: ' 合成\u0000原始地址 ', deliveryName: '合成歷史終點', deliveryAddress: '合成終點地址',
      siteContact: { name: null, phone: null }, plannedStartAt: '1500-01-02T00:00:00.123456+23:59', plannedEndAt: null, requiredTask: true },
    policyMode: 'SYNTHETIC_TEST_ONLY', assignedBy: actorId, assignedAt, assignmentReason: null,
    endedBy: terminal ? actorId : null, endedAt: terminal ? endedAt : null, endReason: terminal ? '合成歷史撤派' : null, previousAssignmentId: null,
    reservations: [
      { id: id(4000 + generation), kind: 'DRIVER', position: 'DRIVER', resourceId: driverId, acquiredAt: assignedAt, releasedAt: terminal ? endedAt : null, releaseReason: terminal ? '合成歷史撤派' : null },
      { id: id(5000 + generation), kind: 'VEHICLE', position: 'MAIN', resourceId: vehicleId, acquiredAt: assignedAt, releasedAt: terminal ? endedAt : null, releaseReason: terminal ? '合成歷史撤派' : null },
    ],
    events: [
      { id: id(6000 + generation), action: 'ASSIGNED', assignmentId, previousAssignmentId: null, fromGeneration: generation - 1, toGeneration: generation, actorId, occurredAt: assignedAt, reason: null },
      ...(terminal ? [{ id: id(7000 + generation), action: 'REVOKED' as const, assignmentId, previousAssignmentId: null, fromGeneration: generation, toGeneration: generation + 1, actorId, occurredAt: endedAt, reason: '合成歷史撤派' }] : []),
    ] }
}
function history(content: Assignment[] = [assignment(5)], patch: Partial<TmsPage<Assignment>> = {}): TmsPage<Assignment> {
  return { content, page: 0, size: 1, totalElements: 3, totalPages: 3, ...patch }
}
function detail(records: TmsPage<Assignment>, generation = 5): DispatchDetail {
  const current = assignment(generation)
  return { task: { taskId, taskNo: current.source.taskNo, orderId, orderNo: current.source.orderNo, orderType: 'LOCAL', orderVersion: 1,
    generation, taskKind: current.source.taskKind, pickupName: current.source.pickupName, deliveryName: current.source.deliveryName,
    prerequisiteReady: true, currentAssignmentId: current.id, policyMode: 'SYNTHETIC_TEST_ONLY', blockingReasons: ['ALREADY_ASSIGNED'] }, current, history: records }
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function props(initial = history(), generation = 5) {
  return { taskId, generation, initial, disabled: false, onExpired: vi.fn(), onStale: vi.fn() }
}
beforeEach(() => { readHistory.mockReset(); vi.stubGlobal('fetch', vi.fn()) })

describe('bounded dispatch history snapshots', () => {
  it('uses the provided initial page without another request and supports an empty history', () => {
    render(<DispatchHistory {...props(history([], { totalElements: 0, totalPages: 0 }))} />)
    expect(screen.getByText('尚無派工歷史。')).toBeVisible()
    expect(screen.getByRole('button', { name: '上一頁歷史' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '下一頁歷史' })).toBeDisabled()
    expect(readHistory).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('pages through assignment generations 5, 3 and 1 without requiring the current assignment on every page', async () => {
    const settings = props(), middle = deferred<DispatchDetail>()
    readHistory.mockReturnValueOnce(middle.promise).mockResolvedValueOnce(detail(history([assignment(1, 'REVOKED')], { page: 2 })))
    render(<DispatchHistory {...settings} />)
    expect(screen.getByText('指派版本 5 · 有效派工 · 合成歷史司機 5 · SYN-HISTORY-5')).toBeVisible()
    const next = screen.getByRole('button', { name: '下一頁歷史' })
    next.focus(); expect(next).toHaveFocus(); expect(next.tagName).toBe('BUTTON')
    fireEvent.click(next)
    expect(screen.queryByText('指派版本 5 · 有效派工 · 合成歷史司機 5 · SYN-HISTORY-5')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('正在讀取派工歷史')
    expect(readHistory).toHaveBeenLastCalledWith(taskId, { historyPage: 1, historySize: 1 }, expect.any(AbortSignal))
    await act(async () => middle.resolve(detail(history([assignment(3, 'REVOKED')], { page: 1 }))))
    expect(await screen.findByText('指派版本 3 · 已撤派 · 合成歷史司機 3 · SYN-HISTORY-3')).toBeVisible()
    expect(screen.queryByText('指派版本 5 · 有效派工 · 合成歷史司機 5 · SYN-HISTORY-5')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '下一頁歷史' }))
    expect(await screen.findByText('指派版本 1 · 已撤派 · 合成歷史司機 1 · SYN-HISTORY-1')).toBeVisible()
    expect(screen.getByRole('button', { name: '下一頁歷史' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '上一頁歷史' })).toBeEnabled()
    expect(settings.onStale).not.toHaveBeenCalled()
    expect(settings.onExpired).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('notifies a newer task generation without displaying or adopting the returned current assignment', async () => {
    const settings = props(), response = detail(history([assignment(5, 'REVOKED')], { page: 1, totalElements: 4, totalPages: 4 }), 7)
    response.current!.driver.name = 'PRIVATE-NEW-CURRENT-DRIVER'
    response.current!.mainVehicle.plateNo = 'PRIVATE-NEW-CURRENT-PLATE'
    readHistory.mockResolvedValue(response)
    const { container } = render(<DispatchHistory {...settings} />)
    fireEvent.click(screen.getByRole('button', { name: '下一頁歷史' }))
    await screen.findByText('指派版本 5 · 已撤派 · 合成歷史司機 5 · SYN-HISTORY-5')
    expect(settings.onStale).toHaveBeenCalledExactlyOnceWith()
    expect(container.textContent).not.toContain('PRIVATE-NEW-CURRENT-DRIVER')
    expect(container.textContent).not.toContain('PRIVATE-NEW-CURRENT-PLATE')
    expect(settings.initial.content[0].assignedGeneration).toBe(5)
    expect(settings.initial.content[0].driver.name).toBe('合成歷史司機 5')
  })

  it('aborts an inflight history read on disabled and ignores its late changed-generation response', async () => {
    const settings = props(), obsolete = deferred<DispatchDetail>(), fresh = deferred<DispatchDetail>()
    readHistory.mockReturnValueOnce(obsolete.promise).mockReturnValueOnce(fresh.promise)
    const view = render(<DispatchHistory {...settings} />)
    fireEvent.click(screen.getByRole('button', { name: '下一頁歷史' }))
    const signal = readHistory.mock.calls[0][2]!
    view.rerender(<DispatchHistory {...settings} disabled />)
    expect(signal.aborted).toBe(true)
    await act(async () => obsolete.resolve(detail(history([assignment(5, 'REVOKED')], { page: 1, totalElements: 4, totalPages: 4 }), 7)))
    expect(settings.onStale).not.toHaveBeenCalled()
    expect(screen.queryByText('指派版本 5 · 已撤派 · 合成歷史司機 5 · SYN-HISTORY-5')).not.toBeInTheDocument()
    expect(readHistory).toHaveBeenCalledTimes(1)
    view.rerender(<DispatchHistory {...settings} generation={7} initial={history([assignment(7)], { totalElements: 4, totalPages: 4 })} />)
    expect(readHistory).toHaveBeenCalledTimes(2)
    await act(async () => fresh.resolve(detail(history([assignment(5, 'REVOKED')], { page: 1, totalElements: 4, totalPages: 4 }), 7)))
    expect(await screen.findByText('指派版本 5 · 已撤派 · 合成歷史司機 5 · SYN-HISTORY-5')).toBeVisible()
    expect(settings.onStale).not.toHaveBeenCalled()
  })

  it('ignores an aborted permission failure after unmount', async () => {
    const settings = props(), pending = deferred<DispatchDetail>()
    readHistory.mockReturnValue(pending.promise)
    const view = render(<DispatchHistory {...settings} />)
    fireEvent.click(screen.getByRole('button', { name: '重新讀取派工歷史' }))
    const signal = readHistory.mock.calls[0][2]!
    view.unmount(); expect(signal.aborted).toBe(true)
    await act(async () => pending.reject(new TmsApiError(401, 'E0401', '合成舊歷史登入失效')))
    expect(settings.onExpired).not.toHaveBeenCalled()
    expect(settings.onStale).not.toHaveBeenCalled()
  })

  it('clears the previous page on a read failure and retries only explicitly', async () => {
    const settings = props()
    readHistory.mockRejectedValueOnce(new TmsApiError(0, 'NETWORK_ERROR', '合成歷史連線中斷'))
      .mockResolvedValueOnce(detail(history([assignment(3, 'REVOKED')], { page: 1 })))
    render(<DispatchHistory {...settings} />)
    fireEvent.click(screen.getByRole('button', { name: '下一頁歷史' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('合成歷史連線中斷')
    expect(screen.queryByText('指派版本 5 · 有效派工 · 合成歷史司機 5 · SYN-HISTORY-5')).not.toBeInTheDocument()
    expect(readHistory).toHaveBeenCalledTimes(1)
    expect(settings.onExpired).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '重新讀取派工歷史' }))
    await screen.findByText('指派版本 3 · 已撤派 · 合成歷史司機 3 · SYN-HISTORY-3')
    expect(readHistory).toHaveBeenLastCalledWith(taskId, { historyPage: 1, historySize: 1 }, expect.any(AbortSignal))
  })

  it.each([
    { name: '401', failure: new TmsApiError(401, 'E0401', '合成歷史登入失效'), exits: true },
    { name: '403 forbidden', failure: new TmsApiError(403, 'E0403', '合成歷史撤權', { reason: 'FORBIDDEN' }), exits: true },
    { name: '403 CSRF', failure: new TmsApiError(403, 'E0403', '合成歷史CSRF拒絕', { reason: 'CSRF_INVALID' }), exits: false },
  ])('clears history and respects the access boundary for $name', async ({ failure, exits }) => {
    const settings = props()
    readHistory.mockRejectedValue(failure)
    render(<DispatchHistory {...settings} />)
    fireEvent.click(screen.getByRole('button', { name: '重新讀取派工歷史' }))
    await screen.findByRole('alert')
    expect(screen.queryByText('指派版本 5 · 有效派工 · 合成歷史司機 5 · SYN-HISTORY-5')).not.toBeInTheDocument()
    if (exits) await waitFor(() => expect(settings.onExpired).toHaveBeenCalledOnce())
    else expect(settings.onExpired).not.toHaveBeenCalled()
    expect(settings.onStale).not.toHaveBeenCalled()
    expect(readHistory).toHaveBeenCalledTimes(1)
  })

  it('keeps the page limit and all disabled controls native', () => {
    const settings = props(history([assignment(1, 'REVOKED')], { page: 100000, totalElements: 100001, totalPages: 100001 }), 200001)
    render(<DispatchHistory {...settings} disabled />)
    for (const name of ['重新讀取派工歷史', '上一頁歷史', '下一頁歷史']) {
      const button = screen.getByRole('button', { name })
      expect(button.tagName).toBe('BUTTON'); expect(button).toBeDisabled()
    }
    expect(readHistory).not.toHaveBeenCalled()
  })
})
