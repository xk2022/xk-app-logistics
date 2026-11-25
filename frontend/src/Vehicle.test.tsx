import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './api'
import type { Account, Role } from './api'
import VehicleManager from './VehicleManager'
import VehicleForm from './VehicleForm'
import { createVehicle, getVehicle, getVehicleOptions, getVehicles, getVehicleTypes, updateVehicle } from './vehicleApi'
import type { Vehicle, VehicleTypeOption } from './vehicleApi'
import { canonicalPlatePreview, inspectionHint, stripVehicleText, validVehicleDate, vehicleFields, vehiclePayload } from './vehicleModel'

vi.mock('./vehicleApi', async (original) => ({ ...await original<typeof import('./vehicleApi')>(), createVehicle: vi.fn(), getVehicle: vi.fn(), getVehicleOptions: vi.fn(), getVehicles: vi.fn(), getVehicleTypes: vi.fn(), updateVehicle: vi.fn() }))
const id = '30000000-0000-4000-8000-000000000001'
const typeA: VehicleTypeOption = { id: '40000000-0000-4000-8000-000000000001', code: 'SYNTHETIC_A', label: '合成車種甲' }
const typeB: VehicleTypeOption = { id: '40000000-0000-4000-8000-000000000002', code: 'SYNTHETIC_B', label: '合成車種乙' }
const actorId = '50000000-0000-4000-8000-000000000001'
const account: Account = { id: actorId, username: 'synthetic.admin', displayName: '合成管理員', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 1 }
function vehicle(patch: Partial<Vehicle> = {}): Vehicle {
  return { id, plateNo: 'SYNTHETIC-A', vehicleType: typeA.id, vehicleTypeCode: typeA.code, vehicleTypeLabel: typeA.label, vehicleTypeEnabled: true, capacityTon: null, registrationNo: null, registrationDate: null, inspectionDueDate: null, administrativeStatus: 'AVAILABLE', version: 2,
    createdAt: '2026-11-25T09:00:00.123456+08:00', updatedAt: '2026-11-25T10:00:00.123456+08:00', createdBy: actorId, updatedBy: actorId, ...patch }
}
function page<T>(content: T[], index = 0, size = 20, totalElements = content.length) { return { content, page: index, size, totalElements, totalPages: Math.ceil(totalElements / size) } }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } }) }
function formProps() { return { role: 'ADMIN' as Role, types: [typeA, typeB], typesLoading: false, onReloadTypes: vi.fn(), onSaved: vi.fn(), onExpired: vi.fn(), onCancel: vi.fn() } }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getVehicleTypes).mockResolvedValue([typeA, typeB])
  vi.mocked(getVehicles).mockResolvedValue(page([vehicle()]))
  vi.mocked(getVehicle).mockResolvedValue(vehicle())
  vi.mocked(getVehicleOptions).mockResolvedValue(page([]))
})

describe('vehicle workspace', () => {
  it('uses literal plate/registration search, precise filters and a real next-page request', async () => {
    vi.mocked(getVehicles).mockImplementation(async (index, query) => page([vehicle({ id: index ? '30000000-0000-4000-8000-000000000002' : id, plateNo: index ? 'SYNTHETIC-B' : 'SYNTHETIC-A' })], index, query?.size ?? 20, 6))
    render(<VehicleManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('button', { name: '檢視車輛 SYNTHETIC-A' })
    fill('車牌搜尋', '  %_\\ e\u0301  '); fill('車種篩選', typeA.id); fill('管理狀態篩選', 'MAINTENANCE'); fill('每頁筆數', '5')
    fireEvent.click(screen.getByRole('button', { name: '搜尋車輛' }))
    await waitFor(() => expect(getVehicles).toHaveBeenLastCalledWith(0, { q: '%_\\ é', vehicleType: typeA.id, administrativeStatus: 'MAINTENANCE', size: 5 }, expect.any(AbortSignal)))
    const pager = screen.getByRole('navigation', { name: '車輛列表分頁' })
    fireEvent.click(within(pager).getByRole('button', { name: '下一頁' }))
    await screen.findByRole('button', { name: '檢視車輛 SYNTHETIC-B' })
    expect(getVehicles).toHaveBeenLastCalledWith(1, { q: '%_\\ é', vehicleType: typeA.id, administrativeStatus: 'MAINTENANCE', size: 5 }, expect.any(AbortSignal))
    expect(screen.getByRole('table', { name: '公司車輛列表' })).toBeVisible()
    expect(screen.queryByRole('option', { name: '使用中' })).not.toBeInTheDocument()
  })

  it.each(['ADMIN', 'DISPATCHER'] as Role[])('explains the empty approved-type catalog to %s without inventing types', async (role) => {
    vi.mocked(getVehicleTypes).mockResolvedValue([])
    vi.mocked(getVehicles).mockResolvedValue(page([]))
    render(<VehicleManager account={{ ...account, role }} onExpired={vi.fn()} />)
    await screen.findByText(/尚無符合條件的車輛/)
    fireEvent.click(screen.getByRole('button', { name: '新增車輛' }))
    expect(screen.getByText('尚無可供新引用的核准車種')).toBeVisible()
    expect(screen.getByText(role === 'ADMIN' ? /請至「系統管理」的 VEHICLE_TYPE 字典/ : /請聯絡管理員建立並啟用經核准車種/)).toBeVisible()
    expect(screen.getByRole('button', { name: '建立車輛' })).toBeDisabled()
    expect(within(screen.getByLabelText('車種', { exact: true })).getAllByRole('option')).toHaveLength(1)
    expect(createVehicle).not.toHaveBeenCalled()
  })

  it.each([{ role: 'DRIVER' as Role, forced: false }, { role: 'ADMIN' as Role, forced: true }])('does not fetch any vehicle data for an unauthorized account $role/$forced', ({ role, forced }) => {
    render(<VehicleManager account={{ ...account, role, mustChangePassword: forced }} onExpired={vi.fn()} />)
    expect(screen.getByRole('heading', { name: '無法使用車輛管理' })).toBeVisible()
    expect(getVehicles).not.toHaveBeenCalled(); expect(getVehicleTypes).not.toHaveBeenCalled(); expect(getVehicleOptions).not.toHaveBeenCalled()
  })

  it('keeps the created form mounted and uses the confirmed vehicle version for subsequent edits', async () => {
    vi.mocked(createVehicle).mockResolvedValue(vehicle({ plateNo: 'SYNTHETIC NEW', version: 0 }))
    vi.mocked(updateVehicle).mockResolvedValue(vehicle({ plateNo: 'SYNTHETIC NEW', version: 1, registrationNo: '合成車籍' }))
    render(<VehicleManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('button', { name: '檢視車輛 SYNTHETIC-A' })
    fireEvent.click(screen.getByRole('button', { name: '新增車輛' }))
    fill('車牌', '  synthetic new  '); fill('車種', typeA.id)
    fireEvent.click(screen.getByRole('button', { name: '建立車輛' }))
    await screen.findByText('車輛已建立。')
    expect(createVehicle).toHaveBeenCalledWith({ plateNo: '  synthetic new  ', vehicleType: typeA.id, capacityTon: null, registrationNo: null, registrationDate: null, inspectionDueDate: null, administrativeStatus: 'AVAILABLE' })
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveValue('SYNTHETIC NEW')
    fill('車籍編號', '合成車籍')
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await screen.findByText('車輛資料已儲存。')
    expect(updateVehicle).toHaveBeenCalledWith(id, expect.objectContaining({ version: 0, registrationNo: '合成車籍' }))
    expect(screen.getByText('已保存版本 1')).toBeVisible()
    expect(createVehicle).toHaveBeenCalledOnce()
  })

  it('requires a second action before leaving locally edited vehicle content', async () => {
    const dirty = vi.fn()
    render(<VehicleManager account={account} onExpired={vi.fn()} onDirtyChange={dirty} />)
    fireEvent.click(await screen.findByRole('button', { name: '檢視車輛 SYNTHETIC-A' }))
    await screen.findByRole('heading', { name: '車輛明細：SYNTHETIC-A' })
    fill('車籍編號', '尚未保存的合成車籍')
    expect(dirty).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: '返回車輛列表' }))
    expect(screen.getByRole('alert', { name: '確認離開車輛' })).toBeVisible()
    expect(screen.getByLabelText('車籍編號')).toHaveValue('尚未保存的合成車籍')
    fireEvent.click(screen.getByRole('button', { name: '繼續編輯車輛' }))
    expect(screen.queryByRole('alert', { name: '確認離開車輛' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '返回車輛列表' }))
    fireEvent.click(screen.getByRole('button', { name: '捨棄本機車輛內容並返回列表' }))
    expect(await screen.findByRole('table', { name: '公司車輛列表' })).toBeVisible()
    expect(screen.queryByLabelText('車籍編號')).not.toBeInTheDocument()
    expect(dirty).toHaveBeenLastCalledWith(false)
  })

  it('blocks an already-open leave confirmation while a save is still in flight', async () => {
    let complete: (saved: Vehicle) => void = () => {}
    const onBusy = vi.fn()
    vi.mocked(updateVehicle).mockImplementation(() => new Promise((resolve) => { complete = resolve }))
    const rendered = render(<VehicleManager account={account} onExpired={vi.fn()} onBusyChange={onBusy} />)
    fireEvent.click(await screen.findByRole('button', { name: '檢視車輛 SYNTHETIC-A' }))
    await screen.findByRole('heading', { name: '車輛明細：SYNTHETIC-A' })
    fill('車籍編號', '保存中的合成內容')
    fireEvent.click(screen.getByRole('button', { name: '返回車輛列表' }))
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    const discard = screen.getByRole('button', { name: '捨棄本機車輛內容並返回列表' })
    expect(discard).toBeDisabled()
    expect(onBusy).toHaveBeenLastCalledWith(true)
    fireEvent.click(discard)
    expect(screen.getByLabelText('車籍編號')).toHaveValue('保存中的合成內容')
    complete(vehicle({ registrationNo: '保存中的合成內容', version: 3 }))
    await screen.findByText('車輛資料已儲存。')
    expect(updateVehicle).toHaveBeenCalledOnce()
    await waitFor(() => expect(discard).not.toBeInTheDocument())
    expect(onBusy).toHaveBeenLastCalledWith(false)
    rendered.unmount()
    expect(onBusy).toHaveBeenLastCalledWith(false)
  })

  it('keeps the selected classification filter visible when that classification is later archived', async () => {
    vi.mocked(getVehicleTypes).mockResolvedValueOnce([typeA, typeB]).mockResolvedValueOnce([typeB])
    render(<VehicleManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('option', { name: `${typeA.label} · ${typeA.code}` })
    fill('車種篩選', typeA.id)
    fireEvent.click(screen.getByRole('button', { name: '搜尋車輛' }))
    fireEvent.click(screen.getByRole('button', { name: '重新載入車輛列表' }))
    await screen.findByRole('option', { name: `${typeA.label}（歷史車種）` })
    expect(screen.getByLabelText('車種篩選')).toHaveValue(typeA.id)
    expect(getVehicles).toHaveBeenLastCalledWith(0, { q: '', size: 20, vehicleType: typeA.id }, expect.any(AbortSignal))
  })

  it('displays available archived classifications without inventing dispatch eligibility', async () => {
    const { registrationNo: omittedRegistration, registrationDate: omittedDate, inspectionDueDate: omittedDue, administrativeStatus: omittedStatus, createdAt: omittedCreated, createdBy: omittedCreator, updatedAt: omittedUpdated, updatedBy: omittedUpdater, ...option } = vehicle({ vehicleTypeEnabled: false, capacityTon: '0' })
    void [omittedRegistration, omittedDate, omittedDue, omittedStatus, omittedCreated, omittedCreator, omittedUpdated, omittedUpdater]
    vi.mocked(getVehicleOptions).mockResolvedValue(page([option], 0, 5))
    render(<VehicleManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('table', { name: '公司車輛列表' })
    fireEvent.click(screen.getByRole('button', { name: '查看主檔可用選項' }))
    const options = await screen.findByRole('region', { name: '主檔可用車輛選項' })
    expect(await within(options).findByText('車種已封存；仍保留主檔可用選項。')).toBeVisible()
    expect(within(options).getByText(/不代表實際可派工/)).toBeVisible()
    fill('可用車輛搜尋', '%_')
    fireEvent.click(within(options).getByRole('button', { name: '搜尋可用車輛' }))
    await waitFor(() => expect(getVehicleOptions).toHaveBeenLastCalledWith(0, { q: '%_', size: 5 }, expect.any(AbortSignal)))
  })

  it('shows read failures, retries explicitly and delegates expired authentication', async () => {
    const expired = vi.fn()
    vi.mocked(getVehicles).mockRejectedValueOnce(new ApiError(503, '合成服務暫時無法使用')).mockRejectedValueOnce(new ApiError(401, '登入狀態失效'))
    render(<VehicleManager account={account} onExpired={expired} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('合成服務暫時無法使用')
    expect(getVehicles).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '重新載入車輛列表' }))
    await waitFor(() => expect(expired).toHaveBeenCalledOnce())
    expect(getVehicles).toHaveBeenCalledTimes(2)
  })
})

describe('vehicle form', () => {
  it('preserves decimal precision and proleptic calendar dates while sending only mutable fields', async () => {
    const props = formProps()
    const current = vehicle({ capacityTon: '9007199254740993.123456789012345', registrationNo: '合成原車籍', registrationDate: '1500-03-01', inspectionDueDate: '0001-01-01' })
    vi.mocked(updateVehicle).mockResolvedValue(vehicle({ ...current, registrationNo: null, version: 3 }))
    render(<VehicleForm {...props} vehicle={current} />)
    fill('車籍編號', '')
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await screen.findByText('車輛資料已儲存。')
    const body = vi.mocked(updateVehicle).mock.calls[0][1]
    expect(body).toEqual({ plateNo: 'SYNTHETIC-A', vehicleType: typeA.id, capacityTon: '9007199254740993.123456789012345', registrationNo: null, registrationDate: '1500-03-01', inspectionDueDate: '0001-01-01', administrativeStatus: 'AVAILABLE', version: 2 })
    expect(Object.keys(body).sort()).toEqual(['administrativeStatus', 'capacityTon', 'inspectionDueDate', 'plateNo', 'registrationDate', 'registrationNo', 'vehicleType', 'version'])
    expect(screen.queryByRole('button', { name: /刪除|派工|使用中/ })).not.toBeInTheDocument()
  })

  it('maintains the same archived type while rejecting a new reference without active choices', async () => {
    const props = { ...formProps(), types: [] }
    vi.mocked(updateVehicle).mockResolvedValue(vehicle({ vehicleTypeEnabled: false, registrationNo: '合成更新', version: 3 }))
    render(<VehicleForm {...props} vehicle={vehicle({ vehicleTypeEnabled: false })} />)
    expect(screen.getByLabelText('車種', { exact: true })).toHaveValue(typeA.id)
    expect(screen.getByText(/原車種「合成車種甲」目前已封存/)).toBeVisible()
    fill('車籍編號', '合成更新')
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await waitFor(() => expect(updateVehicle).toHaveBeenCalledWith(id, expect.objectContaining({ vehicleType: typeA.id, version: 2 })))
    expect(getVehicleTypes).not.toHaveBeenCalled()
  })

  it('retains local conflict edits and loads the fetched version only after explicit confirmation', async () => {
    vi.mocked(updateVehicle).mockRejectedValueOnce(new ApiError(409, '版本已更新')).mockResolvedValueOnce(vehicle({ plateNo: 'CONFIRMED', version: 6 }))
    vi.mocked(getVehicle).mockResolvedValue(vehicle({ plateNo: 'REMOTE', version: 5 }))
    render(<VehicleForm {...formProps()} vehicle={vehicle()} />)
    fill('車牌', 'LOCAL')
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await screen.findByRole('heading', { name: '車輛資料衝突，本機內容已保留' })
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveValue('LOCAL')
    expect(screen.getByRole('button', { name: '儲存車輛' })).toBeDisabled()
    expect(updateVehicle).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '重新讀取車輛' }))
    await screen.findByRole('button', { name: '確認載入最新車輛' })
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveValue('LOCAL')
    fireEvent.click(screen.getByRole('button', { name: '確認載入最新車輛' }))
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveValue('REMOTE')
    fill('車牌', 'CONFIRMED'); fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await screen.findByText('車輛資料已儲存。')
    expect(updateVehicle).toHaveBeenLastCalledWith(id, expect.objectContaining({ version: 5, plateNo: 'CONFIRMED' }))
  })

  it.each([false, true])('permits an explicit plate correction after a duplicate plate failure (existing=$0)', async (existing) => {
    const duplicate = new ApiError(409, '車牌已存在', { plateNo: '合成重複車牌' })
    if (existing) vi.mocked(updateVehicle).mockRejectedValue(duplicate)
    else vi.mocked(createVehicle).mockRejectedValue(duplicate)
    render(<VehicleForm {...formProps()} vehicle={existing ? vehicle() : undefined} />)
    if (!existing) { fill('車牌', 'DUPLICATE'); fill('車種', typeA.id) }
    fireEvent.click(screen.getByRole('button', { name: existing ? '儲存車輛' : '建立車輛' }))
    await screen.findByText('車牌已存在')
    expect(screen.getByLabelText('車牌', { exact: true })).toBeEnabled()
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveAccessibleDescription(/合成重複車牌/)
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveFocus()
    expect(screen.getByRole('button', { name: existing ? '儲存車輛' : '建立車輛' })).toBeEnabled()
    expect(screen.queryByRole('heading', { name: '車輛資料衝突，本機內容已保留' })).not.toBeInTheDocument()
    fill('車牌', 'CORRECTED')
    expect(screen.getByLabelText('車牌', { exact: true })).toHaveValue('CORRECTED')
  })

  it('does not retry a CSRF-rejected mutation and preserves editable input', async () => {
    vi.mocked(updateVehicle).mockRejectedValue(new ApiError(403, '安全驗證失效'))
    const props = formProps()
    render(<VehicleForm {...props} vehicle={vehicle()} />)
    fill('車籍編號', 'CSRF後保留')
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await screen.findByText('安全驗證失效')
    expect(updateVehicle).toHaveBeenCalledOnce()
    expect(screen.getByLabelText('車籍編號')).toHaveValue('CSRF後保留')
    expect(screen.getByLabelText('車籍編號')).toBeEnabled()
    expect(props.onExpired).not.toHaveBeenCalled()
  })

  it.each([false, true])('keeps uncertain results in memory without automatically resubmitting (existing=$0)', async (existing) => {
    if (existing) vi.mocked(updateVehicle).mockRejectedValue(new ApiError(0, '無法確認結果'))
    else vi.mocked(createVehicle).mockRejectedValue(new ApiError(500, '無法確認結果'))
    const dirty = vi.fn()
    render(<VehicleForm {...formProps()} vehicle={existing ? vehicle() : undefined} onDirtyChange={dirty} />)
    if (!existing) { fill('車牌', 'UNCERTAIN'); fill('車種', typeA.id) }
    fill('車籍編號', '原始待確認內容')
    fireEvent.click(screen.getByRole('button', { name: existing ? '儲存車輛' : '建立車輛' }))
    await screen.findByRole('heading', { name: '儲存結果尚未確認' })
    expect(screen.getByLabelText('車籍編號')).toHaveValue('原始待確認內容')
    expect(screen.getByLabelText('車籍編號')).toBeDisabled()
    expect(screen.getByRole('button', { name: existing ? '儲存車輛' : '建立車輛' })).toBeDisabled()
    expect(dirty).toHaveBeenLastCalledWith(true)
    expect(existing ? updateVehicle : createVehicle).toHaveBeenCalledOnce()
  })

  it('delegates session expiry and never sends a second write', async () => {
    vi.mocked(updateVehicle).mockRejectedValue(new ApiError(401, '登入已失效'))
    const props = formProps()
    render(<VehicleForm {...props} vehicle={vehicle()} />)
    fireEvent.click(screen.getByRole('button', { name: '儲存車輛' }))
    await waitFor(() => expect(props.onExpired).toHaveBeenCalledOnce())
    expect(updateVehicle).toHaveBeenCalledOnce()
  })

  it('keeps local values when explicit reload fails and never adopts an unfetched version', async () => {
    vi.mocked(getVehicle).mockRejectedValue(new ApiError(503, '合成讀取失敗'))
    render(<VehicleForm {...formProps()} vehicle={vehicle()} />)
    fill('車籍編號', '保留本機')
    fireEvent.click(screen.getByRole('button', { name: '重新讀取車輛' }))
    await screen.findByText('合成讀取失敗')
    expect(screen.getByLabelText('車籍編號')).toHaveValue('保留本機')
    expect(screen.queryByRole('button', { name: '確認載入最新車輛' })).not.toBeInTheDocument()
    expect(updateVehicle).not.toHaveBeenCalled()
  })
})

describe('vehicle field rules', () => {
  it('validates the canonical codepoint length while sending the exact raw plate for server canonicalization', () => {
    const fields = vehicleFields(vehicle())
    fields.plateNo = `\u001c\uFEFF  ${'🚛'.repeat(40)} \u001f`
    expect(canonicalPlatePreview(fields.plateNo)).toBe('🚛'.repeat(40))
    expect(vehiclePayload(fields).plateNo).toBe(fields.plateNo)
    fields.plateNo = 'ß'.repeat(21)
    expect(() => vehiclePayload(fields)).toThrow()
    fields.plateNo = '  e\u0301 ab-12  '
    expect(canonicalPlatePreview(fields.plateNo)).toBe('É AB-12')
    expect(vehiclePayload(fields).plateNo).toBe(fields.plateNo)
  })
  it.each(['-1', '01', '1e3', '1.5\n', '1.000000000000000000000000000000000'])('rejects invalid decimal %j without coercing or rounding it', (capacity) => {
    const fields = vehicleFields(vehicle()); fields.capacityTon = capacity
    expect(() => vehiclePayload(fields)).toThrow()
  })
  it('distinguishes an unknown load from known zero and preserves historical dates without timezone conversion', () => {
    const fields = vehicleFields(vehicle({ registrationDate: '1500-03-01', inspectionDueDate: '0001-01-01' }))
    expect(vehiclePayload(fields).capacityTon).toBeNull()
    fields.capacityTon = '0.000000000000000000000000000000'
    expect(vehiclePayload(fields).capacityTon).toBe(fields.capacityTon)
    expect(vehiclePayload(fields).registrationDate).toBe('1500-03-01')
    expect(vehiclePayload(fields).inspectionDueDate).toBe('0001-01-01')
    expect(validVehicleDate('1500-02-29')).toBe(false)
    expect(validVehicleDate('1600-02-29')).toBe(true)
    expect(validVehicleDate('2026-11-25\n')).toBe(false)
    expect(validVehicleDate('0000-01-01')).toBe(false)
  })
  it('counts supplementary registration characters, rejects controls/surrogates, and keeps raw mutable text', () => {
    const fields = vehicleFields(vehicle()); fields.registrationNo = `  ${'🚛'.repeat(100)}  `
    expect(vehiclePayload(fields).registrationNo).toBe(fields.registrationNo)
    fields.registrationNo += 'a'
    expect(() => vehiclePayload(fields)).toThrow()
    fields.registrationNo = '合成\u0000車籍'
    expect(() => vehiclePayload(fields)).toThrow()
    fields.registrationNo = '\uD800'
    expect(() => vehiclePayload(fields)).toThrow()
    expect(stripVehicleText('\u0085')).toBe('\u0085')
    expect(stripVehicleText('\u001c\uFEFF a \u001f')).toBe('a')
  })
  it('only provides a date-record hint, with no guessed inspection window', () => {
    const now = new Date('2026-11-24T17:00:00Z')
    expect(inspectionHint('2026-11-24', now)).toBe('已過記錄日期，請確認資料。')
    expect(inspectionHint('2026-11-25', now)).toBe('記錄期限為今日。')
    expect(inspectionHint('2026-11-26', now)).toBeNull()
    expect(inspectionHint(null, now)).toBeNull()
  })
})
