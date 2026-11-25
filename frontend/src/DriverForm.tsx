import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import type { Role } from './api'
import { ErrorNotice } from './AuthForms'
import { createDriver, getDriver, updateDriver } from './driverApi'
import type { Driver, DriverLicenseType } from './driverApi'
import { canonicalDriverCode, driverFields, driverPayload, dutyLabels, employmentLabels } from './driverModel'
import type { DriverFields } from './driverModel'
import DriverAccountPicker from './DriverAccountPicker'

export interface DriverFormProps { driver?: Driver; role: Role; types: DriverLicenseType[]; typesLoading: boolean; typesError?: unknown; onReloadTypes: () => void; onSaved: (driver: Driver) => void; onExpired: () => void; onCancel: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }
const textFields = [['name', '姓名'], ['phone', '電話'], ['email', '電子郵件'], ['licenseReference', '證照參考說明'], ['licenseDueDate', '證照期限']] as const
export default function DriverForm({ driver, role, types, typesLoading, typesError, onReloadTypes, onSaved, onExpired, onCancel, onDirtyChange, onBusyChange }: DriverFormProps) {
  const [baseline, setBaseline] = useState(driver)
  const [fields, setFields] = useState(() => driverFields(driver))
  const [busy, setBusy] = useState(false)
  const [reading, setReading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const [conflict, setConflict] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [remote, setRemote] = useState<Driver | null>(null)
  const [readError, setReadError] = useState<unknown>(null)
  const active = useRef(true)
  const dirtyCallback = useRef(onDirtyChange)
  const busyCallback = useRef(onBusyChange)
  const form = useRef<HTMLFormElement>(null)
  const dirty = busy || uncertain || conflict || JSON.stringify(fields) !== JSON.stringify(driverFields(baseline))
  const locked = busy || reading || uncertain
  const issues = error instanceof ApiError ? error.fields : {}
  const sameType = Boolean(baseline?.licenseType && baseline.licenseType === fields.licenseType)
  const validTypeChoice = fields.licenseType === '' || sameType || !typesLoading && !typesError && types.some((type) => type.id === fields.licenseType)
  useEffect(() => { active.current = true; return () => { active.current = false; dirtyCallback.current?.(false); busyCallback.current?.(false) } }, [])
  useEffect(() => { dirtyCallback.current = onDirtyChange }, [onDirtyChange])
  useEffect(() => { busyCallback.current = onBusyChange }, [onBusyChange])
  useEffect(() => { dirtyCallback.current?.(dirty) }, [dirty])
  useEffect(() => { if (!dirty) return; const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn) }, [dirty])
  useEffect(() => { if (!busy && error instanceof ApiError) { const name = Object.keys(error.fields)[0]; if (name) [...form.current?.querySelectorAll<HTMLElement>('[name]') ?? []].find((element) => element.getAttribute('name') === name)?.focus() } }, [error, busy])
  const change = <K extends keyof DriverFields>(name: K, value: DriverFields[K]) => { if (!locked) { setFields({ ...fields, [name]: value }); setError(null); setNotice(''); setRemote(null) } }
  const adopt = (saved: Driver) => { setBaseline(saved); setFields(driverFields(saved)); setConflict(false); setUncertain(false); setError(null); setRemote(null); setReadError(null) }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (locked || conflict) return
    setError(null); setNotice(''); setRemote(null)
    try {
      const data = driverPayload(fields, !baseline)
      if (!validTypeChoice) throw new ApiError(400, '新增或更換駕照類別須選擇目前有效的分類，或保留未提供。', { licenseType: '請重新讀取類別選項；原封存類別可保留。' })
      if (baseline?.employmentStatus === 'LEFT' && fields.employmentStatus !== 'LEFT') throw new ApiError(409, '離職復職政策尚待核准，不可由本頁恢復在職。', { employmentStatus: '請保留離職狀態；其他欄位仍可維護。' })
      setBusy(true); busyCallback.current?.(true)
      const saved = baseline ? await updateDriver(baseline.id, { ...data, version: baseline.version }) : await createDriver({ ...data, driverCode: fields.driverCode })
      if (active.current) { adopt(saved); setNotice(baseline ? '司機資料已儲存。' : '司機已建立。'); onSaved(saved) }
    } catch (failure) {
      if (!active.current) return
      setError(failure)
      if (failure instanceof ApiError && failure.status === 401) onExpired()
      else if (failure instanceof ApiError && failure.status === 409 && baseline && (Object.keys(failure.fields).length === 0 || Object.hasOwn(failure.fields, 'version'))) setConflict(true)
      else if (!(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500 || failure.status >= 200 && failure.status < 300) setUncertain(true)
    } finally { if (active.current) { setBusy(false); busyCallback.current?.(false) } }
  }
  const read = async () => {
    if (!baseline || busy || reading) return
    setReading(true); busyCallback.current?.(true); setReadError(null); setRemote(null)
    try { const current = await getDriver(baseline.id); if (active.current) setRemote(current) }
    catch (failure) { if (active.current) { setReadError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() } }
    finally { if (active.current) { setReading(false); busyCallback.current?.(false) } }
  }
  const helpId = (name: string) => `driver-${name}-help${issues[name] ? ` driver-${name}-error` : ''}`
  return <section className="card driver-form-card" aria-labelledby="driver-form-heading"><p className="eyebrow">DRIVER MASTER DATA</p><h2 id="driver-form-heading">{baseline ? `司機明細：${baseline.name}` : '新增司機資料'}</h2><p className="page-description">在職、出勤與登入綁定分別管理。本頁不派工、不判定法規資格，也不自動變更帳號。</p>{baseline && <div className="driver-form-meta"><span>司機編號 {baseline.driverCode}</span><span>已保存版本 {baseline.version}</span></div>}{notice && <p className="success-notice" role="status">{notice}</p>}<ErrorNotice error={error} />
    {conflict && <div className="driver-confirm" role="alert"><h3>司機資料衝突，本機內容已保留</h3><p>版本可能已更新。請重新讀取最新資料並明確確認；系統不會自動換版本覆寫或重送。</p></div>}{uncertain && <div className="driver-confirm" role="alert"><h3>儲存結果尚未確認</h3><p>先前請求可能已成功，系統不會自動重送。{baseline ? '請重新讀取司機，確認最新版本後再操作。' : '請返回列表查核司機編號後再操作，避免重複建立。'}本機內容會保留到您明確離開。</p></div>}
    <form ref={form} noValidate aria-label={baseline ? '編輯司機表單' : '建立司機表單'} onSubmit={(event) => { void submit(event) }}><div className="driver-form-grid"><div className="driver-field"><label htmlFor="driver-driverCode">司機編號</label><input id="driver-driverCode" name="driverCode" autoComplete="off" required readOnly={Boolean(baseline)} disabled={locked} value={fields.driverCode} onChange={(event) => change('driverCode', event.target.value)} aria-invalid={issues.driverCode ? true : undefined} aria-describedby={helpId('driverCode')} /><small id="driver-driverCode-help">正規化後 1–40 字元，保留內部空格與連字號，儲存時轉大寫；建立後不可修改或重用。{!baseline && fields.driverCode && canonicalDriverCode(fields.driverCode) !== fields.driverCode ? ` 預覽：${canonicalDriverCode(fields.driverCode)}` : ''}</small>{issues.driverCode && <p className="driver-field-error" id="driver-driverCode-error">{issues.driverCode}</p>}</div>
      {textFields.map(([name, label]) => <div className="driver-field" key={name}><label htmlFor={`driver-${name}`}>{label}</label><input id={`driver-${name}`} name={name} type="text" autoComplete="off" required={name === 'name'} inputMode={name === 'email' ? 'email' : name === 'phone' ? 'tel' : 'text'} disabled={locked} value={fields[name]} placeholder={name === 'licenseDueDate' ? 'YYYY-MM-DD' : undefined} onChange={(event) => change(name, event.target.value)} aria-invalid={issues[name] ? true : undefined} aria-describedby={helpId(name)} /><small id={`driver-${name}-help`}>{name === 'name' ? '姓名 1–200 字元；與登入帳號顯示名稱分開維護。' : name === 'phone' ? '選填，最多 50 字元，可填國碼、空格與分機，不限定國別格式。' : name === 'email' ? '選填，最多 254 字元，只作聯絡文字記錄，不驗證寄送或限定格式。' : name === 'licenseReference' ? '選填工作用參考說明，最多 100 字元；不要求完整證照號碼或身分證字號，沒有證件影像上傳。' : '選填 YYYY-MM-DD 自然日，僅作記錄提示，不判定法規資格或阻擋行政選項。'}</small>{issues[name] && <p className="driver-field-error" id={`driver-${name}-error`}>{issues[name]}</p>}</div>)}
      <div className="driver-field"><label htmlFor="driver-employmentStatus">在職狀態</label><select id="driver-employmentStatus" name="employmentStatus" disabled={locked} value={fields.employmentStatus} onChange={(event) => change('employmentStatus', event.target.value as DriverFields['employmentStatus'])} aria-invalid={issues.employmentStatus ? true : undefined} aria-describedby={helpId('employmentStatus')}>{Object.entries(employmentLabels).map(([value, label]) => <option key={value} value={value} disabled={baseline?.employmentStatus === 'LEFT' && value !== 'LEFT'}>{label}</option>)}</select><small id="driver-employmentStatus-help">在職與停用可切換，兩者可記錄為離職。離職仍可維護資料，復職流程尚未開放；不自動改變出勤或帳號。</small>{issues.employmentStatus && <p className="driver-field-error" id="driver-employmentStatus-error">{issues.employmentStatus}</p>}</div>
      <div className="driver-field"><label htmlFor="driver-dutyStatus">出勤狀態</label><select id="driver-dutyStatus" name="dutyStatus" disabled={locked} value={fields.dutyStatus} onChange={(event) => change('dutyStatus', event.target.value as DriverFields['dutyStatus'])} aria-invalid={issues.dutyStatus ? true : undefined} aria-describedby={helpId('dutyStatus')}>{Object.entries(dutyLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select><small id="driver-dutyStatus-help">獨立記錄出勤，不代表使用中或可派工；停用／離職不會自動清除此值。</small>{issues.dutyStatus && <p className="driver-field-error" id="driver-dutyStatus-error">{issues.dutyStatus}</p>}</div>
      <div className="driver-field driver-field-wide"><label htmlFor="driver-licenseType">駕照類別</label><select id="driver-licenseType" name="licenseType" disabled={locked} value={fields.licenseType} onChange={(event) => change('licenseType', event.target.value)} aria-invalid={issues.licenseType ? true : undefined} aria-describedby={helpId('licenseType')}><option value="">未提供駕照類別</option>{baseline?.licenseType && !types.some((type) => type.id === baseline.licenseType) && <option value={baseline.licenseType}>{baseline.licenseTypeLabel}（原類別）</option>}{types.map((type) => <option key={type.id} value={type.id}>{type.label} · {type.code}</option>)}</select><small id="driver-licenseType-help">類別選填；新增或更換須為有效分類，同一原封存類別可保留，或明確清除。</small>{issues.licenseType && <p className="driver-field-error" id="driver-licenseType-error">{issues.licenseType}</p>}{typesLoading ? <p className="driver-muted" role="status">正在載入駕照類別…</p> : typesError ? <ErrorNotice error={typesError} /> : types.length === 0 && <p className="driver-history-note">尚無核准駕照類別；仍可建立未提供類別的司機主檔。{role === 'ADMIN' ? '請至系統管理維護經核准的 DRIVER_LICENSE_TYPE 字典。' : '如需分類，請聯絡管理員。'}</p>}{baseline?.licenseType && !baseline.licenseTypeEnabled && <p className="driver-history-note">原駕照類別「{baseline.licenseTypeLabel}」已封存；可保留同類別維護，不會因此阻擋本人讀取或行政選項。</p>}<button type="button" className="text-button" disabled={locked} onClick={onReloadTypes}>重新讀取駕照類別</button></div>
    </div><DriverAccountPicker value={fields.accountId} driver={baseline} disabled={locked} issue={issues.accountId} onChange={(value) => change('accountId', value)} onExpired={onExpired} /><div className="driver-form-actions"><p>{busy ? '正在儲存，請等待結果。' : dirty ? '有本機未保存內容或待確認操作。' : '目前內容與已保存資料一致。'}</p><div><button type="button" className="secondary-button" disabled={busy || reading} onClick={onCancel}>返回司機列表</button><button className="primary-button" disabled={locked || conflict || !validTypeChoice}>{busy ? '儲存中…' : baseline ? '儲存司機' : '建立司機'}</button></div></div></form>
    {baseline && <section className="driver-reload" aria-label="重新讀取司機"><button type="button" className="secondary-button" disabled={busy || reading} onClick={() => { void read() }}>{reading ? '讀取司機中…' : '重新讀取司機'}</button><ErrorNotice error={readError} />{remote && <div className="driver-confirm"><h3>已讀取最新司機，尚未覆蓋本機內容</h3><p>司機 {remote.name}，編號 {remote.driverCode}，版本 {remote.version}。確認載入會捨棄本機未保存內容；不會再次送出變更。</p><div className="driver-actions"><button type="button" className="secondary-button" disabled={busy || reading} onClick={() => { if (busy || reading) return; adopt(remote); setNotice('已載入最新司機資料。'); onSaved(remote) }}>確認載入最新司機</button><button type="button" className="text-button" disabled={busy || reading} onClick={() => setRemote(null)}>保留本機司機內容</button></div></div>}</section>}
    {baseline && <details className="driver-identity"><summary>司機識別與異動資訊</summary><dl><div><dt>司機識別</dt><dd>{baseline.id}</dd></div><div><dt>建立時間</dt><dd><time dateTime={baseline.createdAt}>{baseline.createdAt}</time></dd></div><div><dt>更新時間</dt><dd><time dateTime={baseline.updatedAt}>{baseline.updatedAt}</time></dd></div></dl></details>}
  </section>
}
