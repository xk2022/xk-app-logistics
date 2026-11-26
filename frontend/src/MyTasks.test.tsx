import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './api'
import MyTasks from './MyTasks'
import { getMyTmsTasks, TmsApiError } from './tmsApi'
import type { MyTask, TmsPage } from './tmsApi'

vi.mock('./tmsApi', async (original) => ({ ...await original<typeof import('./tmsApi')>(), getMyTmsTasks: vi.fn() }))
const readTasks = vi.mocked(getMyTmsTasks)
const id = (number: number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
function task(patch: Partial<MyTask> = {}): MyTask {
  return { assignmentId: id(1), taskId: id(101), taskNo: 'SYNTHETIC-MY-TASK-1',
    item: { itemKind: 'CONTAINER', containerType: '20GP', containerNo: 'SYNTHETIC-CONTAINER', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' },
    taskKind: '合成運送作業', pickupName: '合成起點甲', pickupAddress: ' 合成\u0000原始地址 e\u0301\r\n二樓 ',
    deliveryName: '合成終點甲', deliveryAddress: '合成終點地址', siteContact: { name: '合成現場聯絡人', phone: '合成分機 A' },
    plannedStartAt: '1500-01-02T00:00:00.123456+23:59', plannedEndAt: null,
    assignedAt: '2026-11-26T01:02:03.123456Z', policyMode: 'SYNTHETIC_TEST_ONLY', ...patch }
}
function page(content: MyTask[] = [task()], patch: Partial<TmsPage<MyTask>> = {}): TmsPage<MyTask> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, ...patch }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let visibility: DocumentVisibilityState = 'visible'
function changeVisibility(value: DocumentVisibilityState) { visibility = value; fireEvent(document, new Event('visibilitychange')) }
function expectOldDataHidden() {
  expect(screen.queryByRole('article', { name: '本人任務 SYNTHETIC-MY-TASK-1' })).not.toBeInTheDocument()
  expect(screen.queryByText('合成起點甲')).not.toBeInTheDocument()
  expect(screen.queryByText('合成現場聯絡人')).not.toBeInTheDocument()
  expect(screen.queryByText('合成分機 A')).not.toBeInTheDocument()
}

beforeEach(() => {
  readTasks.mockReset()
  visibility = 'visible'
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
  vi.stubGlobal('fetch', vi.fn())
})
afterEach(() => { vi.restoreAllMocks() })

describe('actor-derived readonly current tasks', () => {
  it('uses only the self reader and exposes no dispatch or reporting operations', async () => {
    readTasks.mockResolvedValue(page())
    const { container } = render(<MyTasks onExpired={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('正在重新確認本人有效任務')
    const card = await screen.findByRole('article', { name: '本人任務 SYNTHETIC-MY-TASK-1' })
    expect(within(card).getByText('有效派工 · 唯讀')).toBeVisible()
    expect(screen.getByRole('complementary', { name: '合成工程驗證' })).toHaveTextContent('不代表正式營運或法規適格核准')
    expect(readTasks).toHaveBeenCalledExactlyOnceWith(0, { size: 20 }, expect.any(AbortSignal))
    expect(fetch).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /出發|回報|照片|派車|改派|撤派|儲存/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(container.querySelector('input,textarea,form')).toBeNull()
    expect(screen.getByRole('combobox')).toBe(screen.getByLabelText('每頁本人任務數'))
  })

  it('preserves source text, NUL, decimal precision and raw offset/microsecond timestamps', async () => {
    const source = task({ item: { ...task().item, grossWeight: '9007199254740993.123456789012345' } })
    readTasks.mockResolvedValue(page([source]))
    const { container } = render(<MyTasks onExpired={vi.fn()} />)
    await screen.findByText('SYNTHETIC-MY-TASK-1')
    expect([...container.querySelectorAll('.my-tasks-raw')].map((node) => node.textContent)).toContain(source.pickupAddress)
    expect(screen.getByText('9007199254740993.123456789012345')).toBeVisible()
    expect(screen.getByText('0', { exact: true })).toBeVisible()
    const planned = container.querySelector(`time[datetime="${source.plannedStartAt}"]`)
    expect(planned).toHaveTextContent(source.plannedStartAt!)
    expect(planned?.getAttribute('datetime')).toBe(source.plannedStartAt)
    expect(container.querySelector(`time[datetime="${source.assignedAt}"]`)).toBeInTheDocument()
    expect(screen.getByText('台北時間', { exact: true })).toBeVisible()
    expect(source.pickupAddress).toBe(' 合成\u0000原始地址 e\u0301\r\n二樓 ')
  })

  it('distinguishes absent contact, present null fields, empty strings and zero from unknown', async () => {
    readTasks.mockResolvedValue(page([
      task({ siteContact: null }),
      task({ assignmentId: id(2), taskId: id(102), taskNo: 'SYNTHETIC-MY-TASK-2', siteContact: { name: null, phone: null } }),
      task({ assignmentId: id(3), taskId: id(103), taskNo: 'SYNTHETIC-MY-TASK-3', siteContact: { name: '', phone: null } }),
    ]))
    render(<MyTasks onExpired={vi.fn()} />)
    const absent = within(await screen.findByRole('article', { name: '本人任務 SYNTHETIC-MY-TASK-1' })).getByRole('region', { name: '本段現場聯絡' })
    expect(within(absent).getByText('未提供整組現場聯絡資訊。')).toBeVisible()
    expect(within(absent).queryByText('現場聯絡姓名')).not.toBeInTheDocument()
    const empty = within(screen.getByRole('article', { name: '本人任務 SYNTHETIC-MY-TASK-2' })).getByRole('region', { name: '本段現場聯絡' })
    expect(within(empty).getByText('已提供現場聯絡欄位。')).toBeVisible()
    expect(within(empty).getAllByText('未提供')).toHaveLength(2)
    const blank = within(screen.getByRole('article', { name: '本人任務 SYNTHETIC-MY-TASK-3' })).getByRole('region', { name: '本段現場聯絡' })
    expect(within(blank).getByText('空字串')).toBeVisible()
    expect(within(blank).getAllByText('未提供')).toHaveLength(1)
  })

  it('renders source values as text and never displays extra management or financial data', async () => {
    const input = { ...task(), pickupAddress: '<img src="/private-image" onerror="alert(1)">',
      customerName: 'PRIVATE-CUSTOMER', orderId: 'PRIVATE-ORDER', driverId: 'PRIVATE-DRIVER',
      accountId: 'PRIVATE-ACCOUNT', plateNo: 'PRIVATE-VEHICLE', licenseReference: 'PRIVATE-LICENSE',
      remark: 'PRIVATE-ORDER-REMARK', financialTotal: 'PRIVATE-MONEY', history: ['PRIVATE-HISTORY'] }
    readTasks.mockResolvedValue(page([input]))
    const { container } = render(<MyTasks onExpired={vi.fn()} />)
    await screen.findByText(input.pickupAddress)
    expect(container.querySelector('img')).toBeNull()
    for (const value of ['PRIVATE-CUSTOMER', 'PRIVATE-ORDER', 'PRIVATE-DRIVER', 'PRIVATE-ACCOUNT', 'PRIVATE-VEHICLE', 'PRIVATE-LICENSE', 'PRIVATE-ORDER-REMARK', 'PRIVATE-MONEY', 'PRIVATE-HISTORY']) expect(container.textContent).not.toContain(value)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('keeps unconfirmed policies explicit without displaying a dispatch or legal guarantee', async () => {
    readTasks.mockResolvedValue(page([task({ policyMode: 'UNCONFIRMED' })]))
    render(<MyTasks onExpired={vi.fn()} />)
    expect(await screen.findByText('資格政策尚待確認，請向調度員核對。')).toBeVisible()
    expect(screen.queryByRole('complementary', { name: '合成工程驗證' })).not.toBeInTheDocument()
    expect(screen.queryByText(/可出發|合法適格|保證可派/)).not.toBeInTheDocument()
  })
})

describe('fresh reads and immediate privacy clearing', () => {
  it('hides old route/contact before refreshing and keeps them removed after an empty result', async () => {
    const next = deferred<TmsPage<MyTask>>()
    readTasks.mockResolvedValueOnce(page()).mockReturnValueOnce(next.promise)
    render(<MyTasks onExpired={vi.fn()} />)
    await screen.findByText('合成現場聯絡人')
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    expectOldDataHidden()
    expect(screen.getByRole('status')).toHaveTextContent('正在重新確認本人有效任務')
    await act(async () => next.resolve(page([])))
    expect(await screen.findByRole('heading', { name: '目前沒有有效派工' })).toBeVisible()
    expectOldDataHidden()
    expect(readTasks).toHaveBeenCalledTimes(2)
  })

  it.each([
    { label: '401', failure: new TmsApiError(401, 'E0401', '合成登入失效') },
    { label: 'FORBIDDEN', failure: new TmsApiError(403, 'E0403', '合成撤權', { reason: 'FORBIDDEN' }) },
    { label: 'missing reason', failure: new TmsApiError(403, 'E0403', '合成未知拒絕') },
    { label: 'unknown reason', failure: new TmsApiError(403, 'E0403', '合成新拒絕', { reason: 'FUTURE_ACCESS_DENIAL' }) },
    { label: 'generic 403', failure: new ApiError(403, '合成舊錯誤類型') },
  ])('clears contents and safely exits on $label', async ({ failure }) => {
    const expired = vi.fn(), next = deferred<TmsPage<MyTask>>()
    readTasks.mockResolvedValueOnce(page()).mockReturnValueOnce(next.promise)
    render(<MyTasks onExpired={expired} />)
    await screen.findByText('合成現場聯絡人')
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    expectOldDataHidden()
    await act(async () => next.reject(failure))
    await waitFor(() => expect(expired).toHaveBeenCalledOnce())
    expect(screen.getByRole('heading', { name: '本人任務存取已失效' })).toBeVisible()
    expectOldDataHidden()
    expect(readTasks).toHaveBeenCalledTimes(2)
  })

  it('clears a CSRF refusal and only retries after a new explicit read', async () => {
    const expired = vi.fn()
    readTasks.mockResolvedValueOnce(page()).mockRejectedValueOnce(new TmsApiError(403, 'E0403', '合成CSRF拒絕', { reason: 'CSRF_INVALID' }))
      .mockResolvedValueOnce(page([task({ pickupName: '合成重新確認起點', siteContact: null })]))
    render(<MyTasks onExpired={expired} />)
    await screen.findByText('合成現場聯絡人')
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    await screen.findByText('安全驗證暫時無法完成，請重新讀取。')
    expectOldDataHidden()
    expect(expired).not.toHaveBeenCalled()
    expect(readTasks).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    expect(await screen.findByText('合成重新確認起點')).toBeVisible()
    expect(readTasks).toHaveBeenCalledTimes(3)
  })

  it('treats missing current binding as an empty help state while retaining login', async () => {
    const expired = vi.fn()
    readTasks.mockResolvedValueOnce(page()).mockRejectedValueOnce(new TmsApiError(404, 'E0404', '合成綁定已解除'))
    render(<MyTasks onExpired={expired} />)
    await screen.findByText('合成現場聯絡人')
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    expect(await screen.findByRole('heading', { name: '目前沒有可讀取的本人任務' })).toBeVisible()
    expect(screen.getByText(/請聯絡管理員確認目前帳號綁定/)).toBeVisible()
    expectOldDataHidden()
    expect(expired).not.toHaveBeenCalled()
  })

  it.each([new TmsApiError(500, 'E0500', '合成服務中斷'), new Error('合成連線中斷'), undefined])('clears prior data after transport/server/unknown failure without retrying itself', async (failure) => {
    const expired = vi.fn()
    readTasks.mockResolvedValueOnce(page()).mockRejectedValueOnce(failure)
    render(<MyTasks onExpired={expired} />)
    await screen.findByText('合成現場聯絡人')
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    expect(await screen.findByRole('heading', { name: '無法讀取本人任務' })).toBeVisible()
    expectOldDataHidden()
    expect(expired).not.toHaveBeenCalled()
    expect(readTasks).toHaveBeenCalledTimes(2)
  })

  it('removes background data, aborts superseded reads and ignores late results after returning', async () => {
    const stale = deferred<TmsPage<MyTask>>(), fresh = deferred<TmsPage<MyTask>>()
    readTasks.mockResolvedValueOnce(page()).mockReturnValueOnce(stale.promise).mockReturnValueOnce(fresh.promise)
    render(<MyTasks onExpired={vi.fn()} />)
    await screen.findByText('合成現場聯絡人')
    changeVisibility('hidden')
    expectOldDataHidden()
    expect(screen.getByRole('status')).toHaveTextContent('頁面回到前景後')
    expect(readTasks).toHaveBeenCalledTimes(1)
    changeVisibility('visible')
    expect(readTasks).toHaveBeenCalledTimes(2)
    const oldSignal = readTasks.mock.calls[1][2]!
    changeVisibility('hidden'); expect(oldSignal.aborted).toBe(true)
    changeVisibility('visible')
    expect(readTasks).toHaveBeenCalledTimes(3)
    await act(async () => fresh.resolve(page([task({ taskNo: 'SYNTHETIC-FRESH-TASK', pickupName: '合成目前起點', siteContact: null })])))
    expect(await screen.findByText('合成目前起點')).toBeVisible()
    await act(async () => stale.resolve(page()))
    expectOldDataHidden()
    expect(screen.getByText('合成目前起點')).toBeVisible()
    expect(readTasks).toHaveBeenLastCalledWith(0, { size: 20 }, expect.any(AbortSignal))
  })

  it('does not fetch in the background on initial mount and reads when visible', async () => {
    visibility = 'hidden'; readTasks.mockResolvedValue(page([]))
    render(<MyTasks onExpired={vi.fn()} />)
    expect(readTasks).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '重新讀取本人任務' })).toBeDisabled()
    changeVisibility('visible')
    expect(await screen.findByRole('heading', { name: '目前沒有有效派工' })).toBeVisible()
    expect(readTasks).toHaveBeenCalledExactlyOnceWith(0, { size: 20 }, expect.any(AbortSignal))
  })

  it('aborts on unmount and never expires a new session from a discarded response', async () => {
    const pending = deferred<TmsPage<MyTask>>(), expired = vi.fn()
    readTasks.mockReturnValue(pending.promise)
    const view = render(<MyTasks onExpired={expired} />)
    const signal = readTasks.mock.calls[0][2]!
    view.unmount()
    expect(signal.aborted).toBe(true)
    await act(async () => pending.reject(new TmsApiError(401, 'E0401', '合成舊請求過期')))
    expect(expired).not.toHaveBeenCalled()
  })

  it('uses the latest expiration callback without adding an identity lookup or extra read', async () => {
    const pending = deferred<TmsPage<MyTask>>(), original = vi.fn(), latest = vi.fn()
    readTasks.mockReturnValue(pending.promise)
    const view = render(<MyTasks onExpired={original} />)
    view.rerender(<MyTasks onExpired={latest} />)
    expect(readTasks).toHaveBeenCalledTimes(1)
    await act(async () => pending.reject(new TmsApiError(401, 'E0401', '合成目前登入失效')))
    await waitFor(() => expect(latest).toHaveBeenCalledOnce())
    expect(original).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })
})

describe('bounded native pagination controls', () => {
  it('clears the previous page, preserves focusable controls and resets page on size/refresh', async () => {
    const first = Array.from({ length: 20 }, (_, index) => task({ assignmentId: id(index + 1), taskId: id(index + 101), taskNo: `SYNTHETIC-PAGE-0-${index}`, pickupName: `合成第0頁起點-${index}`, siteContact: null }))
    const next = deferred<TmsPage<MyTask>>()
    readTasks.mockResolvedValueOnce(page(first, { totalElements: 21, totalPages: 2 })).mockReturnValueOnce(next.promise)
      .mockResolvedValueOnce(page([task({ taskNo: 'SYNTHETIC-SIZE-50', pickupName: '合成50筆頁面', siteContact: null })], { size: 50 }))
      .mockResolvedValueOnce(page([] , { size: 50 }))
    render(<MyTasks onExpired={vi.fn()} />)
    await screen.findByText('SYNTHETIC-PAGE-0-0')
    const nextButton = within(screen.getByRole('navigation', { name: '本人任務分頁' })).getByRole('button', { name: '下一頁本人任務' })
    nextButton.focus(); expect(nextButton).toHaveFocus(); expect(nextButton.tagName).toBe('BUTTON')
    expect(screen.getByRole('button', { name: '上一頁本人任務' })).toBeDisabled()
    fireEvent.click(nextButton)
    expect(screen.queryByText('合成第0頁起點-0')).not.toBeInTheDocument()
    expect(readTasks).toHaveBeenLastCalledWith(1, { size: 20 }, expect.any(AbortSignal))
    await act(async () => next.resolve(page([task({ taskNo: 'SYNTHETIC-PAGE-1', pickupName: '合成第1頁起點', siteContact: null })], { page: 1, totalElements: 21, totalPages: 2 })))
    await screen.findByText('合成第1頁起點')
    expect(screen.getByRole('button', { name: '上一頁本人任務' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '下一頁本人任務' })).toBeDisabled()
    const size = screen.getByLabelText('每頁本人任務數')
    size.focus(); expect(size).toHaveFocus(); expect(size.tagName).toBe('SELECT')
    fireEvent.change(size, { target: { value: '50' } })
    expect(screen.queryByText('合成第1頁起點')).not.toBeInTheDocument()
    await screen.findByText('合成50筆頁面')
    expect(readTasks).toHaveBeenLastCalledWith(0, { size: 50 }, expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人任務' }))
    await screen.findByRole('heading', { name: '目前沒有有效派工' })
    expect(readTasks).toHaveBeenLastCalledWith(0, { size: 50 }, expect.any(AbortSignal))
  })
})
