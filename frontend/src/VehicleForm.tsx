import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import type { Role } from './api'
import { ErrorNotice } from './AuthForms'
import { createVehicle, getVehicle, updateVehicle } from './vehicleApi'
import type { Vehicle, VehicleTypeOption } from './vehicleApi'
import { canonicalPlatePreview, vehicleFields, vehiclePayload, vehicleStatusLabels } from './vehicleModel'
import type { VehicleFields } from './vehicleModel'

export interface VehicleFormProps { vehicle?: Vehicle; role: Role; types: VehicleTypeOption[]; typesLoading: boolean; typesError?: unknown; onReloadTypes: () => void; onSaved: (vehicle: Vehicle) => void; onExpired: () => void; onCancel: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }
const textFields = [['plateNo', '車牌'], ['capacityTon', '載重（噸）'], ['registrationNo', '車籍編號'], ['registrationDate', '車籍登記日'], ['inspectionDueDate', '檢驗期限']] as const
export default function VehicleForm({ vehicle, role, types, typesLoading, typesError, onReloadTypes, onSaved, onExpired, onCancel, onDirtyChange, onBusyChange }: VehicleFormProps) {
  const [baseline, setBaseline] = useState(vehicle)
  const [fields, setFields] = useState(() => vehicleFields(vehicle))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const [conflict, setConflict] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [reading, setReading] = useState(false)
  const [remote, setRemote] = useState<Vehicle | null>(null)
  const [readError, setReadError] = useState<unknown>(null)
  const active = useRef(true)
  const callback = useRef(onDirtyChange)
  const busyCallback = useRef(onBusyChange)
  const form = useRef<HTMLFormElement>(null)
  const dirty = busy || uncertain || conflict || JSON.stringify(fields) !== JSON.stringify(vehicleFields(baseline))
  const locked = busy || reading || uncertain
  const issues = error instanceof ApiError ? error.fields : {}
  const originalType = baseline?.vehicleType === fields.vehicleType
  const activeType = types.some((type) => type.id === fields.vehicleType)
  const inactiveHistoricalType = baseline && !typesLoading && !typesError && !types.some((type) => type.id === baseline.vehicleType)
  useEffect(() => { active.current = true; return () => { active.current = false; callback.current?.(false); busyCallback.current?.(false) } }, [])
  useEffect(() => { callback.current = onDirtyChange }, [onDirtyChange])
  useEffect(() => { busyCallback.current = onBusyChange }, [onBusyChange])
  useEffect(() => { callback.current?.(dirty) }, [dirty])
  useEffect(() => { if (!dirty) return; const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn) }, [dirty])
  useEffect(() => { if (!busy && error instanceof ApiError) { const name = Object.keys(error.fields)[0]; if (name) [...form.current?.querySelectorAll<HTMLElement>('[name]') ?? []].find((element) => element.getAttribute('name') === name)?.focus() } }, [error, busy])
  const change = <K extends keyof VehicleFields>(name: K, value: VehicleFields[K]) => { if (!locked) { setFields({ ...fields, [name]: value }); setError(null); setNotice(''); setRemote(null) } }
  const adopt = (saved: Vehicle) => { setBaseline(saved); setFields(vehicleFields(saved)); setConflict(false); setUncertain(false); setError(null); setRemote(null); setReadError(null) }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (locked || conflict) return
    setError(null); setNotice(''); setRemote(null)
    try {
      const data = vehiclePayload(fields)
      if (!originalType && (!activeType || typesLoading || typesError)) throw new ApiError(400, '新增或更換車種須選擇目前有效的車種。', { vehicleType: '請重新讀取車種選項，或聯絡管理員。' })
      setBusy(true); busyCallback.current?.(true)
      const saved = baseline ? await updateVehicle(baseline.id, { ...data, version: baseline.version }) : await createVehicle(data)
      if (active.current) { adopt(saved); setNotice(baseline ? '車輛資料已儲存。' : '車輛已建立。'); onSaved(saved) }
    } catch (failure) {
      if (!active.current) return
      setError(failure)
      if (failure instanceof ApiError && failure.status === 401) onExpired()
      else if (failure instanceof ApiError && failure.status === 409 && baseline && !Object.hasOwn(failure.fields, 'plateNo')) setConflict(true)
      else if (!(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500) setUncertain(true)
    } finally { if (active.current) { setBusy(false); busyCallback.current?.(false) } }
  }
  const read = async () => {
    if (!baseline || busy || reading) return
    setReading(true); busyCallback.current?.(true); setReadError(null); setRemote(null)
    try { const current = await getVehicle(baseline.id); if (active.current) setRemote(current) }
    catch (failure) { if (active.current) { setReadError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() } }
    finally { if (active.current) { setReading(false); busyCallback.current?.(false) } }
  }
  const describedBy = (name: string) => `vehicle-${name}-help${issues[name] ? ` vehicle-${name}-error` : ''}`
  return <section className="card vehicle-form-card" aria-labelledby="vehicle-form-title"><p className="eyebrow">VEHICLE MASTER DATA</p><h2 id="vehicle-form-title">{baseline ? `車輛明細：${baseline.plateNo}` : '新增車輛資料'}</h2><p className="page-description">管理主檔與行政狀態；不代表即時可派工，也不建立使用中或占用資料。</p>
    {baseline && <div className="vehicle-form-meta"><span>已保存版本 {baseline.version}</span><span className="vehicle-form-status">管理狀態 <strong className={`vehicle-status vehicle-status-${baseline.administrativeStatus}`}>{vehicleStatusLabels[baseline.administrativeStatus]}</strong></span></div>}
    {notice && <p className="success-notice" role="status">{notice}</p>}<ErrorNotice error={error} />
    {conflict && <div className="vehicle-confirm" role="alert"><h3>車輛資料衝突，本機內容已保留</h3><p>車牌可能重複或版本已更新。請讀取最新資料，比較並明確確認後再操作；系統不會自動覆寫或重送。</p></div>}
    {uncertain && <div className="vehicle-confirm" role="alert"><h3>儲存結果尚未確認</h3><p>先前請求可能已成功，系統不會自動重送。{baseline ? '請重新讀取車輛，確認最新版本後再操作。' : '請返回列表查核車牌後再操作，避免建立重複主檔。'}</p></div>}
    <form ref={form} aria-label={baseline ? '編輯車輛表單' : '建立車輛表單'} noValidate onSubmit={(event) => { void submit(event) }}>
      <div className="vehicle-form-grid">
        <div className="vehicle-field"><label htmlFor="vehicle-plateNo">車牌</label><input id="vehicle-plateNo" name="plateNo" required autoComplete="off" value={fields.plateNo} disabled={locked} onChange={(event) => change('plateNo', event.target.value)} aria-invalid={issues.plateNo ? true : undefined} aria-describedby={describedBy('plateNo')} /><small id="vehicle-plateNo-help">儲存時移除首尾空白、統一字形並轉為大寫，結果為 1–40 字元；保留內部空格、連字號，不限定單國格式。{fields.plateNo && canonicalPlatePreview(fields.plateNo) !== fields.plateNo ? ` 預覽：${canonicalPlatePreview(fields.plateNo)}` : ''}</small>{issues.plateNo && <p className="vehicle-field-error" id="vehicle-plateNo-error">{issues.plateNo}</p>}</div>
        <div className="vehicle-field"><label htmlFor="vehicle-vehicleType">車種</label><select id="vehicle-vehicleType" name="vehicleType" required value={fields.vehicleType} disabled={locked} onChange={(event) => change('vehicleType', event.target.value)} aria-invalid={issues.vehicleType ? true : undefined} aria-describedby={describedBy('vehicleType')}><option value="">請選擇車種</option>{baseline && !types.some((type) => type.id === baseline.vehicleType) && <option value={baseline.vehicleType}>{baseline.vehicleTypeLabel}（原車種）</option>}{types.map((type) => <option key={type.id} value={type.id}>{type.label} · {type.code}</option>)}</select><small id="vehicle-vehicleType-help">新增或更換車種只可選有效分類；原車種已停用仍可維護此車輛。</small>{issues.vehicleType && <p className="vehicle-field-error" id="vehicle-vehicleType-error">{issues.vehicleType}</p>}{typesLoading ? <p role="status" className="vehicle-muted">正在載入車種…</p> : typesError ? <><ErrorNotice error={typesError} /><button type="button" className="text-button" disabled={locked} onClick={onReloadTypes}>重新讀取車種</button></> : types.length === 0 && <div className="vehicle-type-empty"><strong>尚無可供新引用的核准車種</strong><p>{role === 'ADMIN' ? '請至「系統管理」的 VEHICLE_TYPE 字典建立經核准車種並啟用分類及項目。' : '請聯絡管理員建立並啟用經核准車種；此處不提供一般字典管理權限。'}</p><button type="button" className="text-button" disabled={locked} onClick={onReloadTypes}>重新讀取車種</button></div>}{baseline && (!baseline.vehicleTypeEnabled || inactiveHistoricalType) && <p className="vehicle-history-note">原車種「{baseline.vehicleTypeLabel}」目前已封存或停用；保留同一車種仍可維護車牌、車籍及管理狀態，不會自動改分類。</p>}</div>
        {textFields.filter(([name]) => name !== 'plateNo').map(([name, label]) => <div className="vehicle-field" key={name}><label htmlFor={`vehicle-${name}`}>{label}</label><input id={`vehicle-${name}`} name={name} autoComplete="off" inputMode={name === 'capacityTon' ? 'decimal' : 'text'} value={fields[name]} disabled={locked} onChange={(event) => change(name, event.target.value)} aria-invalid={issues[name] ? true : undefined} aria-describedby={describedBy(name)} placeholder={name.endsWith('Date') ? 'YYYY-MM-DD' : undefined} /><small id={`vehicle-${name}-help`}>{name === 'capacityTon' ? '選填非負十進位文字，最多 32 字；保留精度，0 表示已知零，留空表示未知。' : name === 'registrationNo' ? '選填，正規化後最多 100 字元；不限定國別或證件格式。' : '選填 YYYY-MM-DD 自然日；只作記錄提示，不判定法規合規或派工資格。'}</small>{issues[name] && <p className="vehicle-field-error" id={`vehicle-${name}-error`}>{issues[name]}</p>}</div>)}
        <div className="vehicle-field"><label htmlFor="vehicle-administrativeStatus">管理狀態</label><select id="vehicle-administrativeStatus" name="administrativeStatus" value={fields.administrativeStatus} disabled={locked} onChange={(event) => change('administrativeStatus', event.target.value as VehicleFields['administrativeStatus'])} aria-invalid={issues.administrativeStatus ? true : undefined} aria-describedby={describedBy('administrativeStatus')}>{Object.entries(vehicleStatusLabels).map(([status, label]) => <option key={status} value={status}>{label}</option>)}</select><small id="vehicle-administrativeStatus-help">可用、維修、停用是人工管理狀態；沒有手動「使用中」或派工占用操作。</small>{issues.administrativeStatus && <p className="vehicle-field-error" id="vehicle-administrativeStatus-error">{issues.administrativeStatus}</p>}</div>
      </div>
      <div className="vehicle-form-actions"><p>{busy ? '正在儲存，請等待結果。' : dirty ? '有本機未保存內容或待確認操作。' : '目前內容與已保存資料一致。'}</p><div><button className="secondary-button" type="button" disabled={busy || reading} onClick={onCancel}>返回車輛列表</button><button className="primary-button" disabled={locked || conflict || !originalType && (!activeType || typesLoading || Boolean(typesError))}>{busy ? '儲存中…' : baseline ? '儲存車輛' : '建立車輛'}</button></div></div>
    </form>
    {baseline && <section className="vehicle-reload" aria-label="重新讀取車輛"><button type="button" className="secondary-button" disabled={busy || reading} onClick={() => { void read() }}>{reading ? '讀取車輛中…' : '重新讀取車輛'}</button><ErrorNotice error={readError} />{remote && <div className="vehicle-confirm"><h3>已讀取最新車輛，尚未覆蓋本機內容</h3><p>車牌 {remote.plateNo}，版本 {remote.version}。確認載入會捨棄本機未保存內容，改用此版本；不會再次送出變更。</p><div className="vehicle-actions"><button type="button" className="secondary-button" disabled={busy || reading} onClick={() => { if (busy || reading) return; adopt(remote); setNotice('已載入最新車輛資料。'); onSaved(remote) }}>確認載入最新車輛</button><button type="button" className="text-button" disabled={busy || reading} onClick={() => setRemote(null)}>保留本機車輛內容</button></div></div>}</section>}
    {baseline && <details className="vehicle-identity"><summary>車輛識別與異動資訊</summary><dl><div><dt>車輛識別</dt><dd>{baseline.id}</dd></div><div><dt>車種識別</dt><dd>{baseline.vehicleType}</dd></div><div><dt>建立時間</dt><dd><time dateTime={baseline.createdAt}>{baseline.createdAt}</time></dd></div><div><dt>更新時間</dt><dd><time dateTime={baseline.updatedAt}>{baseline.updatedAt}</time></dd></div></dl></details>}
  </section>
}
