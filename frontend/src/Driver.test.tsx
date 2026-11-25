import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './api'
import type { Account, Role } from './api'
import DriverManager from './DriverManager'
import DriverForm from './DriverForm'
import DriverSelf from './DriverSelf'
import { createDriver, getDriver, getDriverAccountOptions, getDriverLicenseTypes, getDriverMe, getDriverOptions, getDrivers, updateDriver } from './driverApi'
import type { Driver, DriverAccountOption, DriverLicenseType, DriverSelf as SelfData, DriverSummary } from './driverApi'
import { canonicalDriverCode, driverFields, driverPayload, licenseDateHint, stripDriverText, validDriverDate } from './driverModel'

vi.mock('./driverApi', async (original) => ({ ...await original<typeof import('./driverApi')>(), createDriver: vi.fn(), getDriver: vi.fn(), getDriverAccountOptions: vi.fn(), getDriverLicenseTypes: vi.fn(), getDriverMe: vi.fn(), getDriverOptions: vi.fn(), getDrivers: vi.fn(), updateDriver: vi.fn() }))
const id = '60000000-0000-4000-8000-000000000001'
const actorId = '70000000-0000-4000-8000-000000000001'
const typeA: DriverLicenseType = { id: '80000000-0000-4000-8000-000000000001', code: 'SYNTHETIC_A', label: '合成類別甲' }
const typeB: DriverLicenseType = { id: '80000000-0000-4000-8000-000000000002', code: 'SYNTHETIC_B', label: '合成類別乙' }
const freeAccount: DriverAccountOption = { id: '90000000-0000-4000-8000-000000000001', username: 'synthetic.driver.a', displayName: '合成登入甲', bound: false }
const occupiedAccount: DriverAccountOption = { id: '90000000-0000-4000-8000-000000000002', username: 'synthetic.driver.b', displayName: '合成登入乙', bound: true }
const temporaryAccount: DriverAccountOption = { id: '90000000-0000-4000-8000-000000000003', username: 'synthetic.temporary', displayName: '合成臨時帳號', bound: false }
const account: Account = { id: actorId, username: 'synthetic.manager', displayName: '合成管理員', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 1 }
function driver(patch: Partial<Driver> = {}): Driver {
  return { id, driverCode: 'SYNTHETIC-D', name: '合成司機甲', phone: null, email: null, accountId: null, accountUsername: null, accountDisplayName: null, accountBindingValid: false, employmentStatus: 'ACTIVE', dutyStatus: 'OFF_DUTY', licenseType: null, licenseTypeCode: null, licenseTypeLabel: null, licenseTypeEnabled: false, licenseReference: null, licenseDueDate: null, version: 2, createdAt: '2026-11-25T09:00:00.123456+08:00', createdBy: actorId, updatedAt: '2026-11-25T10:00:00.123456+08:00', updatedBy: actorId, ...patch }
}
function summary(value: Driver): DriverSummary {
  const { licenseReference, createdAt, createdBy, updatedAt, updatedBy, ...result } = value
  void [licenseReference, createdAt, createdBy, updatedAt, updatedBy]
  return result
}
function selfData(value = driver()): SelfData {
  const { id, driverCode, name, phone, email, employmentStatus, dutyStatus, licenseType, licenseTypeCode, licenseTypeLabel, licenseTypeEnabled, licenseDueDate } = value
  return { id, driverCode, name, phone, email, employmentStatus, dutyStatus, licenseType, licenseTypeCode, licenseTypeLabel, licenseTypeEnabled, licenseDueDate }
}
function page<T>(content: T[], index = 0, size = 20, totalElements = content.length) { return { content, page: index, size, totalElements, totalPages: Math.ceil(totalElements / size) } }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } }) }
function formProps() { return { role: 'ADMIN' as Role, types: [typeA, typeB], typesLoading: false, onReloadTypes: vi.fn(), onSaved: vi.fn(), onExpired: vi.fn(), onCancel: vi.fn() } }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getDriverLicenseTypes).mockResolvedValue([typeA, typeB])
  vi.mocked(getDrivers).mockResolvedValue(page([summary(driver())]))
  vi.mocked(getDriver).mockResolvedValue(driver())
  vi.mocked(getDriverAccountOptions).mockResolvedValue(page([freeAccount, occupiedAccount, temporaryAccount], 0, 5))
  vi.mocked(getDriverOptions).mockResolvedValue(page([]))
  vi.mocked(getDriverMe).mockResolvedValue(selfData())
})

describe('driver management workspace', () => {
  it('uses literal search, independent status filters, license UUID and real page requests', async () => {
    vi.mocked(getDrivers).mockImplementation(async (index, query) => page([summary(driver({ driverCode: index ? 'SYNTHETIC-NEXT' : 'SYNTHETIC-D' }))], index, query?.size ?? 20, 6))
    render(<DriverManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('button', { name: '檢視司機 SYNTHETIC-D' })
    fill('司機搜尋', '  %_\\ e\u0301  '); fill('在職狀態篩選', 'INACTIVE'); fill('出勤狀態篩選', 'ON_DUTY'); fill('駕照類別篩選', typeA.id); fill('每頁筆數', '5')
    fireEvent.click(screen.getByRole('button', { name: '搜尋司機' }))
    await waitFor(() => expect(getDrivers).toHaveBeenLastCalledWith(0, { q: '%_\\ é', employmentStatus: 'INACTIVE', dutyStatus: 'ON_DUTY', licenseType: typeA.id, size: 5 }, expect.any(AbortSignal)))
    fireEvent.click(within(screen.getByRole('navigation', { name: '司機列表分頁' })).getByRole('button', { name: '下一頁' }))
    await screen.findByRole('button', { name: '檢視司機 SYNTHETIC-NEXT' })
    expect(getDrivers).toHaveBeenLastCalledWith(1, expect.objectContaining({ employmentStatus: 'INACTIVE', dutyStatus: 'ON_DUTY', size: 5 }), expect.any(AbortSignal))
    expect(screen.getByRole('table', { name: '公司司機列表' })).toBeVisible()
  })

  it.each(['ADMIN', 'DISPATCHER'] as Role[])('allows %s to create the smallest unbound driver with an empty license catalog', async (role) => {
    vi.mocked(getDriverLicenseTypes).mockResolvedValue([])
    vi.mocked(getDriverAccountOptions).mockResolvedValue(page([], 0, 5))
    vi.mocked(createDriver).mockResolvedValue(driver({ driverCode: 'SYNTHETIC NEW', version: 0 }))
    render(<DriverManager account={{ ...account, role }} onExpired={vi.fn()} />)
    await screen.findByRole('table', { name: '公司司機列表' })
    fireEvent.click(screen.getByRole('button', { name: '新增司機' }))
    fill('司機編號', '  synthetic new  '); fill('姓名', '合成司機甲')
    expect(screen.getByLabelText('電話', { exact: true })).not.toBeRequired()
    expect(screen.getByLabelText('駕照類別', { exact: true })).not.toBeRequired()
    fireEvent.click(screen.getByRole('button', { name: '建立司機' }))
    await screen.findByText('司機已建立。')
    expect(createDriver).toHaveBeenCalledWith({ driverCode: '  synthetic new  ', name: '合成司機甲', phone: null, email: null, accountId: null, employmentStatus: 'ACTIVE', dutyStatus: 'OFF_DUTY', licenseType: null, licenseReference: null, licenseDueDate: null })
    expect(screen.getByLabelText('司機編號')).toHaveValue('SYNTHETIC NEW')
    expect(screen.getByLabelText('司機編號')).toHaveAttribute('readonly')
  })

  it.each([{ role: 'DRIVER' as Role, forced: false }, { role: 'ADMIN' as Role, forced: true }])('never fetches management data for unauthorized $role/$forced', ({ role, forced }) => {
    render(<DriverManager account={{ ...account, role, mustChangePassword: forced }} onExpired={vi.fn()} />)
    expect(screen.getByRole('heading', { name: '無法使用司機管理' })).toBeVisible()
    expect(getDrivers).not.toHaveBeenCalled(); expect(getDriverLicenseTypes).not.toHaveBeenCalled(); expect(getDriverAccountOptions).not.toHaveBeenCalled(); expect(getDriverMe).not.toHaveBeenCalled()
  })

  it('keeps the confirmed create mounted and uses its version for the next PATCH without resending driverCode', async () => {
    vi.mocked(createDriver).mockResolvedValue(driver({ version: 0 }))
    vi.mocked(updateDriver).mockResolvedValue(driver({ version: 1, phone: '+44 0000 ext A' }))
    render(<DriverManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('table', { name: '公司司機列表' })
    fireEvent.click(screen.getByRole('button', { name: '新增司機' }))
    fill('司機編號', 'SYNTHETIC-D'); fill('姓名', '合成司機甲')
    fireEvent.click(screen.getByRole('button', { name: '建立司機' }))
    await screen.findByText('司機已建立。')
    fill('電話', '+44 0000 ext A'); fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    expect(updateDriver).toHaveBeenCalledWith(id, expect.objectContaining({ version: 0, phone: '+44 0000 ext A' }))
    expect(vi.mocked(updateDriver).mock.calls[0][1]).not.toHaveProperty('driverCode')
    expect(createDriver).toHaveBeenCalledOnce()
  })

  it('preserves dirty content and blocks an old leave banner during an in-flight write', async () => {
    let complete: (value: Driver) => void = () => {}
    vi.mocked(updateDriver).mockImplementation(() => new Promise((resolve) => { complete = resolve }))
    const busy = vi.fn(), dirty = vi.fn()
    render(<DriverManager account={account} onExpired={vi.fn()} onBusyChange={busy} onDirtyChange={dirty} />)
    fireEvent.click(await screen.findByRole('button', { name: '檢視司機 SYNTHETIC-D' }))
    await screen.findByRole('heading', { name: '司機明細：合成司機甲' })
    fill('姓名', '本機待保存姓名')
    fireEvent.click(screen.getByRole('button', { name: '返回司機列表' }))
    fireEvent.click(screen.getByRole('button', { name: '繼續編輯司機' }))
    expect(screen.getByLabelText('姓名')).toHaveValue('本機待保存姓名')
    fireEvent.click(screen.getByRole('button', { name: '返回司機列表' }))
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    const discard = screen.getByRole('button', { name: '捨棄本機司機內容並返回列表' })
    expect(discard).toBeDisabled(); expect(busy).toHaveBeenLastCalledWith(true)
    fireEvent.click(discard)
    expect(screen.getByLabelText('姓名')).toHaveValue('本機待保存姓名')
    complete(driver({ name: '本機待保存姓名', version: 3 }))
    await screen.findByText('司機資料已儲存。')
    await waitFor(() => expect(discard).not.toBeInTheDocument())
    expect(busy).toHaveBeenLastCalledWith(false); expect(dirty).toHaveBeenLastCalledWith(false)
  })

  it('keeps an archived selected filter visible and never displays licenseReference in the list', async () => {
    vi.mocked(getDriverLicenseTypes).mockResolvedValueOnce([typeA, typeB]).mockResolvedValueOnce([typeB])
    render(<DriverManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('option', { name: `${typeA.label} · ${typeA.code}` })
    fill('駕照類別篩選', typeA.id)
    fireEvent.click(screen.getByRole('button', { name: '搜尋司機' }))
    fireEvent.click(screen.getByRole('button', { name: '重新載入司機列表' }))
    await screen.findByRole('option', { name: `${typeA.label}（歷史類別）` })
    expect(screen.getByLabelText('駕照類別篩選')).toHaveValue(typeA.id)
    expect(screen.queryByLabelText('證照參考說明')).not.toBeInTheDocument()
    expect(screen.queryByText('合成私有參考說明')).not.toBeInTheDocument()
  })

  it('shows administrative sources without adding account, date, or archived-category restrictions', async () => {
    vi.mocked(getDriverOptions).mockResolvedValue(page([{ id, driverCode: 'SYNTHETIC-D', name: '合成行政來源', licenseType: typeA.id, licenseTypeCode: typeA.code, licenseTypeLabel: typeA.label, licenseTypeEnabled: false, licenseDueDate: '0001-01-01', version: 3 }], 0, 5))
    render(<DriverManager account={account} onExpired={vi.fn()} />)
    await screen.findByRole('table', { name: '公司司機列表' })
    fireEvent.click(screen.getByRole('button', { name: '查看行政可用司機選項' }))
    const options = await screen.findByRole('region', { name: '行政可用司機選項' })
    expect(await within(options).findByText('合成行政來源')).toBeVisible()
    expect(within(options).getByText('駕照類別已封存；仍保留行政可用選項。')).toBeVisible()
    expect(within(options).getByText(/未綁定帳號、證照日期或封存類別不額外排除/)).toBeVisible()
    fill('行政可用司機搜尋', '%_'); fireEvent.click(within(options).getByRole('button', { name: '搜尋行政可用司機' }))
    await waitFor(() => expect(getDriverOptions).toHaveBeenLastCalledWith(0, { q: '%_', size: 5 }, expect.any(AbortSignal)))
    expect(getDriverAccountOptions).not.toHaveBeenCalled()
  })
})

describe('driver form and narrow account binding', () => {
  it('shows occupied candidates disabled, keeps page totals and allows an eligible temporary account', async () => {
    vi.mocked(createDriver).mockResolvedValue(driver({ accountId: temporaryAccount.id, accountUsername: temporaryAccount.username, accountDisplayName: temporaryAccount.displayName, accountBindingValid: true, version: 0 }))
    render(<DriverForm {...formProps()} />)
    const occupied = await screen.findByRole('option', { name: `${occupiedAccount.displayName} · ${occupiedAccount.username}（已綁定，不可選）` })
    expect(occupied).toBeDisabled()
    expect(within(screen.getByRole('navigation', { name: '可綁定帳號分頁' })).getByText(/共 3 筆/)).toBeVisible()
    fill('司機編號', 'SYNTHETIC-D'); fill('姓名', '合成司機甲'); fill('綁定登入帳號', temporaryAccount.id)
    fireEvent.click(screen.getByRole('button', { name: '建立司機' }))
    await screen.findByText('司機已建立。')
    expect(createDriver).toHaveBeenCalledWith(expect.objectContaining({ accountId: temporaryAccount.id }))
    expect(screen.getByText(/有效臨時帳號可綁定/)).toBeVisible()
  })

  it('paginates and searches candidates inside the driver form without accidentally submitting a mutation', async () => {
    vi.mocked(getDriverAccountOptions).mockImplementation(async (index) => page(index ? [temporaryAccount] : [freeAccount, occupiedAccount], index, 5, 6))
    render(<DriverForm {...formProps()} />)
    await screen.findByRole('option', { name: `${freeAccount.displayName} · ${freeAccount.username}` })
    fill('司機編號', 'SYNTHETIC-D'); fill('姓名', '合成司機甲'); fill('綁定登入帳號', freeAccount.id)
    fireEvent.click(screen.getByRole('button', { name: '下一頁帳號' }))
    await screen.findByRole('option', { name: `${temporaryAccount.displayName} · ${temporaryAccount.username}` })
    expect(getDriverAccountOptions).toHaveBeenLastCalledWith(1, { q: '', size: 5 }, expect.any(AbortSignal))
    expect(screen.getByLabelText('綁定登入帳號')).toHaveValue(freeAccount.id)
    fill('綁定帳號搜尋', '  %_  ')
    fireEvent.keyDown(screen.getByLabelText('綁定帳號搜尋'), { key: 'Enter' })
    await waitFor(() => expect(getDriverAccountOptions).toHaveBeenLastCalledWith(0, { q: '%_', size: 5 }, expect.any(AbortSignal)))
    expect(createDriver).not.toHaveBeenCalled(); expect(updateDriver).not.toHaveBeenCalled()
  })

  it('retains an original occupied or currently invalid binding during unrelated maintenance', async () => {
    const current = driver({ accountId: occupiedAccount.id, accountUsername: occupiedAccount.username, accountDisplayName: occupiedAccount.displayName, accountBindingValid: false })
    vi.mocked(updateDriver).mockResolvedValue(driver({ ...current, name: '合成維護後姓名', version: 3 }))
    render(<DriverForm {...formProps()} driver={current} />)
    expect(await screen.findByRole('option', { name: `${occupiedAccount.displayName} · ${occupiedAccount.username}（原綁定）` })).toBeEnabled()
    expect(screen.getByLabelText('綁定登入帳號')).toHaveValue(occupiedAccount.id)
    fill('姓名', '合成維護後姓名'); fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    expect(updateDriver).toHaveBeenCalledWith(id, expect.objectContaining({ accountId: occupiedAccount.id, version: 2 }))
    expect(screen.getByText(/原綁定帳號目前無法讀取本人資料/)).toBeVisible()
  })

  it('keeps an invalid original account absent from all eligible candidates without inventing a replacement', async () => {
    vi.mocked(getDriverAccountOptions).mockResolvedValue(page([], 0, 5))
    const original = driver({ accountId: occupiedAccount.id, accountUsername: occupiedAccount.username, accountDisplayName: occupiedAccount.displayName, accountBindingValid: false })
    vi.mocked(updateDriver).mockResolvedValue(driver({ ...original, phone: '合成維護分機', version: 3 }))
    render(<DriverForm {...formProps()} driver={original} />)
    await screen.findByText(/此頁沒有符合條件的帳號/)
    expect(screen.getByLabelText('綁定登入帳號')).toHaveValue(occupiedAccount.id)
    expect(screen.getByRole('option', { name: `${occupiedAccount.displayName} · ${occupiedAccount.username}（原綁定）` })).toBeEnabled()
    fill('電話', '合成維護分機'); fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    expect(updateDriver).toHaveBeenCalledWith(id, expect.objectContaining({ accountId: occupiedAccount.id, phone: '合成維護分機', version: 2 }))
    expect(createDriver).not.toHaveBeenCalled()
  })

  it('requires an explicit save for unbinding and never calls an UPMS lifecycle operation', async () => {
    const current = driver({ accountId: freeAccount.id, accountUsername: freeAccount.username, accountDisplayName: freeAccount.displayName, accountBindingValid: true })
    vi.mocked(updateDriver).mockResolvedValue(driver({ version: 3 }))
    render(<DriverForm {...formProps()} driver={current} />)
    await screen.findByRole('option', { name: `${freeAccount.displayName} · ${freeAccount.username}` })
    fill('綁定登入帳號', '')
    expect(screen.getByText(/儲存這次解綁後/)).toBeVisible()
    expect(updateDriver).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    const body = vi.mocked(updateDriver).mock.calls[0][1]
    expect(body.accountId).toBeNull()
    expect(Object.keys(body).sort()).toEqual(['accountId', 'dutyStatus', 'email', 'employmentStatus', 'licenseDueDate', 'licenseReference', 'licenseType', 'name', 'phone', 'version'])
    expect(screen.queryByLabelText('密碼')).not.toBeInTheDocument()
  })

  it('keeps account-occupied conflicts editable without changing the baseline version or retrying automatically', async () => {
    vi.mocked(updateDriver).mockRejectedValue(new ApiError(409, '帳號已有其他綁定', { accountId: '請選另一個帳號' }))
    render(<DriverForm {...formProps()} driver={driver()} />)
    await screen.findByRole('option', { name: `${freeAccount.displayName} · ${freeAccount.username}` })
    fill('綁定登入帳號', freeAccount.id)
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('帳號已有其他綁定')
    expect(screen.getByLabelText('綁定登入帳號')).toHaveAccessibleDescription(/請選另一個帳號/)
    expect(screen.getByLabelText('綁定登入帳號')).toHaveFocus()
    expect(screen.getByRole('button', { name: '儲存司機' })).toBeEnabled()
    expect(screen.queryByRole('heading', { name: '司機資料衝突，本機內容已保留' })).not.toBeInTheDocument()
    expect(updateDriver).toHaveBeenCalledOnce()
    expect(vi.mocked(updateDriver).mock.calls[0][1].version).toBe(2)
  })

  it('preserves a historical license type and independent LEFT/ON_DUTY records without offering reinstatement', async () => {
    const current = driver({ employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY', licenseType: typeA.id, licenseTypeCode: typeA.code, licenseTypeLabel: typeA.label, licenseTypeEnabled: false, licenseDueDate: '1500-03-01', licenseReference: '合成工作參考' })
    vi.mocked(updateDriver).mockResolvedValue(driver({ ...current, name: '合成離職資料維護', version: 3 }))
    render(<DriverForm {...formProps()} types={[]} driver={current} />)
    const employment = screen.getByLabelText('在職狀態', { exact: true })
    expect(within(employment).getByRole('option', { name: '在職' })).toBeDisabled()
    expect(within(employment).getByRole('option', { name: '停用' })).toBeDisabled()
    expect(screen.getByLabelText('出勤狀態', { exact: true })).toHaveValue('ON_DUTY')
    expect(screen.getByLabelText('駕照類別', { exact: true })).toHaveValue(typeA.id)
    fill('姓名', '合成離職資料維護'); fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    expect(updateDriver).toHaveBeenCalledWith(id, expect.objectContaining({ employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY', licenseType: typeA.id, licenseDueDate: '1500-03-01', licenseReference: '合成工作參考' }))
  })

  it('does not automatically alter duty when employment changes, and permits descriptive contact text', async () => {
    vi.mocked(updateDriver).mockResolvedValue(driver({ employmentStatus: 'INACTIVE', dutyStatus: 'ON_DUTY', email: '內部轉交：聯絡窗口', version: 3 }))
    render(<DriverForm {...formProps()} driver={driver({ dutyStatus: 'ON_DUTY' })} />)
    fill('在職狀態', 'INACTIVE'); fill('電子郵件', '內部轉交：聯絡窗口')
    expect(screen.getByLabelText('電子郵件')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('出勤狀態', { exact: true })).toHaveValue('ON_DUTY')
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    expect(updateDriver).toHaveBeenCalledWith(id, expect.objectContaining({ employmentStatus: 'INACTIVE', dutyStatus: 'ON_DUTY', email: '內部轉交：聯絡窗口' }))
  })

  it('keeps local version-conflict edits until the latest driver is explicitly loaded', async () => {
    vi.mocked(updateDriver).mockRejectedValueOnce(new ApiError(409, '版本已更新', { version: '請重讀' })).mockResolvedValueOnce(driver({ name: '確認後內容', version: 6 }))
    vi.mocked(getDriver).mockResolvedValue(driver({ name: '遠端第5版', version: 5 }))
    render(<DriverForm {...formProps()} driver={driver()} />)
    fill('姓名', '本機未保存')
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByRole('heading', { name: '司機資料衝突，本機內容已保留' })
    expect(screen.getByLabelText('姓名')).toHaveValue('本機未保存')
    expect(screen.getByRole('button', { name: '儲存司機' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '重新讀取司機' }))
    await screen.findByRole('button', { name: '確認載入最新司機' })
    expect(screen.getByLabelText('姓名')).toHaveValue('本機未保存')
    fireEvent.click(screen.getByRole('button', { name: '確認載入最新司機' }))
    expect(screen.getByLabelText('姓名')).toHaveValue('遠端第5版')
    fill('姓名', '確認後內容'); fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('司機資料已儲存。')
    expect(updateDriver).toHaveBeenLastCalledWith(id, expect.objectContaining({ version: 5, name: '確認後內容' }))
  })

  it.each([0, 201, 500])('retains unknown write results (status %s) without new writes or an automatic version change', async (status) => {
    vi.mocked(updateDriver).mockRejectedValue(new ApiError(status, '合成未知結果'))
    render(<DriverForm {...formProps()} driver={driver()} />)
    fill('姓名', '結果未知仍保留')
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByRole('heading', { name: '儲存結果尚未確認' })
    expect(screen.getByLabelText('姓名')).toHaveValue('結果未知仍保留')
    expect(screen.getByLabelText('姓名')).toBeDisabled()
    expect(screen.getByRole('button', { name: '儲存司機' })).toBeDisabled()
    expect(updateDriver).toHaveBeenCalledOnce()
    expect(vi.mocked(updateDriver).mock.calls[0][1].version).toBe(2)
  })

  it('keeps a CSRF rejection editable and delegates 401 without auto-resubmitting', async () => {
    vi.mocked(updateDriver).mockRejectedValueOnce(new ApiError(403, '安全驗證失效')).mockRejectedValueOnce(new ApiError(401, '登入失效'))
    const props = formProps()
    render(<DriverForm {...props} driver={driver()} />)
    fill('姓名', '保留本機')
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await screen.findByText('安全驗證失效')
    expect(screen.getByLabelText('姓名')).toBeEnabled()
    expect(screen.getByLabelText('姓名')).toHaveValue('保留本機')
    expect(updateDriver).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '儲存司機' }))
    await waitFor(() => expect(props.onExpired).toHaveBeenCalledOnce())
    expect(updateDriver).toHaveBeenCalledTimes(2)
  })
})

describe('driver self view', () => {
  it('only calls the actor-derived self endpoint and keeps LEFT/ON_DUTY data read only', async () => {
    vi.mocked(getDriverMe).mockResolvedValue(selfData(driver({ employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY', phone: '+44 0000 ext A', licenseType: typeA.id, licenseTypeCode: typeA.code, licenseTypeLabel: typeA.label, licenseTypeEnabled: false, licenseDueDate: '0001-01-01' })))
    render(<DriverSelf onExpired={vi.fn()} />)
    expect(await screen.findByRole('heading', { name: '合成司機甲' })).toBeVisible()
    expect(screen.getAllByText('離職')).toHaveLength(2)
    expect(screen.getByText('出勤')).toBeVisible()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument(); expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByText('證照參考說明')).not.toBeInTheDocument(); expect(screen.queryByText('已保存版本')).not.toBeInTheDocument()
    expect(getDriverMe).toHaveBeenCalledWith(expect.any(AbortSignal))
    expect(getDrivers).not.toHaveBeenCalled(); expect(getDriverLicenseTypes).not.toHaveBeenCalled(); expect(getDriverAccountOptions).not.toHaveBeenCalled(); expect(getDriver).not.toHaveBeenCalled()
  })

  it.each([403, 404])('immediately hides stale self data and keeps it cleared after %s', async (status) => {
    let reject: (reason: unknown) => void = () => {}
    vi.mocked(getDriverMe).mockResolvedValueOnce(selfData()).mockImplementationOnce(() => new Promise((_, rejectPromise) => { reject = rejectPromise }))
    const expired = vi.fn()
    render(<DriverSelf onExpired={expired} />)
    await screen.findByRole('heading', { name: '合成司機甲' })
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人資料' }))
    expect(screen.queryByRole('heading', { name: '合成司機甲' })).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('正在讀取本人司機資料')
    reject(new ApiError(status, '無本人資料'))
    await screen.findByRole('heading', { name: '目前沒有可顯示的本人司機資料' })
    expect(screen.queryByText('SYNTHETIC-D')).not.toBeInTheDocument()
    expect(expired).not.toHaveBeenCalled()
  })

  it('clears self contents on session expiry and exposes an explicit read retry after transport failure', async () => {
    const expired = vi.fn()
    vi.mocked(getDriverMe).mockRejectedValueOnce(new ApiError(0, '合成連線中斷')).mockRejectedValueOnce(new ApiError(401, '登入已撤銷'))
    render(<DriverSelf onExpired={expired} />)
    await screen.findByRole('heading', { name: '無法讀取本人司機資料' })
    fireEvent.click(screen.getByRole('button', { name: '重新讀取本人資料' }))
    await waitFor(() => expect(expired).toHaveBeenCalledOnce())
    expect(screen.queryByText('合成司機甲')).not.toBeInTheDocument()
    expect(getDriverMe).toHaveBeenCalledTimes(2)
  })
})

describe('driver field rules', () => {
  it('validates canonical codepoint limits without mutating the raw create code', () => {
    const fields = driverFields(driver())
    fields.driverCode = `\u001c\uFEFF  ${'🚛'.repeat(40)} \u001f`
    expect(canonicalDriverCode(fields.driverCode)).toBe('🚛'.repeat(40))
    expect(() => driverPayload(fields, true)).not.toThrow()
    expect(fields.driverCode).toContain('\u001c')
    fields.driverCode = 'ß'.repeat(21)
    expect(() => driverPayload(fields, true)).toThrow()
    fields.driverCode = '  e\u0301 code-1  '
    expect(canonicalDriverCode(fields.driverCode)).toBe('É CODE-1')
  })
  it('retains descriptive email, null-clears optional fields and counts normalized Unicode codepoints', () => {
    const fields = driverFields(driver())
    fields.name = `  ${'🚛'.repeat(200)}  `; fields.email = '聯絡窗口（待確認）'; fields.licenseDueDate = '1500-03-01'
    expect(driverPayload(fields, false)).toMatchObject({ name: fields.name, email: fields.email, phone: null, accountId: null, licenseType: null, licenseDueDate: '1500-03-01' })
    fields.name += 'a'
    expect(() => driverPayload(fields, false)).toThrow()
    fields.name = '合成姓名\u0000'
    expect(() => driverPayload(fields, false)).toThrow()
    fields.name = '\uD800'
    expect(() => driverPayload(fields, false)).toThrow()
    expect(stripDriverText('\u001c\uFEFF a \u001f')).toBe('a')
    expect(stripDriverText('\u0085')).toBe('\u0085')
  })
  it('uses Gregorian natural days and plain date hints without a guessed license window', () => {
    expect(validDriverDate('1500-02-29')).toBe(false); expect(validDriverDate('1600-02-29')).toBe(true)
    expect(validDriverDate('0001-01-01')).toBe(true); expect(validDriverDate('0000-01-01')).toBe(false)
    expect(validDriverDate('2026-11-25\n')).toBe(false)
    const now = new Date('2026-11-24T17:00:00Z')
    expect(licenseDateHint('2026-11-24', now)).toBe('已過記錄日期，請確認資料。')
    expect(licenseDateHint('2026-11-25', now)).toBe('記錄期限為今日。')
    expect(licenseDateHint('2026-11-26', now)).toBeNull()
  })
})
