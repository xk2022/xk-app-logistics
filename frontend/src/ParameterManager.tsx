import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { createParameter, getDefinitions, getParameters, updateParameter, validateParameterValue } from './admApi'
import type { Definition, Parameter } from './admApi'
import { AdmError, Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'

export default function ParameterManager({ onExpired }: { onExpired: () => void }) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const { loading, data, error: loadError } = useAdmPage(getParameters, page, revision, onExpired)
  const [catalog, setCatalog] = useState<{ revision: number; definitions?: Definition[]; error?: unknown }>()
  const [editing, setEditing] = useState<Parameter | null>(null)
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [valueText, setValueText] = useState('')
  const [description, setDescription] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    void getDefinitions(controller.signal).then((definitions) => { if (active) setCatalog({ revision, definitions }) })
      .catch((failure: unknown) => { if (active) { setCatalog({ revision, error: failure }); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [revision, onExpired])
  const catalogLoading = catalog?.revision !== revision
  const definitions = catalogLoading ? undefined : catalog?.definitions
  const definition = definitions?.find((item) => item.key === key)
  const clear = () => { setEditing(null); setKey(''); setName(''); setValueText(''); setDescription(''); setEnabled(true) }
  const reload = () => { clear(); setError(null); setRevision((value) => value + 1) }
  const choose = (key: string) => {
    const selected = definitions?.find((item) => item.key === key)
    setKey(key); setName(selected?.name ?? ''); setDescription(selected?.description ?? ''); setValueText(selected?.valueType === 'BOOLEAN' ? 'false' : ''); setEnabled(true); setError(null)
  }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!definition) return
    setBusy(true); setError(null); setNotice('')
    try {
      const failure = validateParameterValue(valueText, definition)
      if (failure) throw new ApiError(400, failure)
      if (editing) await updateParameter(editing.id, { name, valueText, description, enabled, version: editing.version })
      else await createParameter({ key: definition.key, name, valueType: definition.valueType, valueText, description, enabled })
      setNotice(editing ? '系統參數已更新。' : '系統參數已建立。'); clear(); setRevision((value) => value + 1)
    } catch (failure) { setError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() }
    finally { setBusy(false) }
  }
  return <div>
    <div className="section-title"><div><h1>系統參數</h1><p className="page-description">僅管理核准業務用途的參數，不存機密、憑證或安全政策。</p></div><button className="secondary-button" disabled={busy || loading || catalogLoading} onClick={reload}>重新載入參數</button></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}<AdmError error={error ?? loadError ?? (!catalogLoading && catalog?.error)} />
    {catalogLoading ? <p role="status">正在載入核准參數定義…</p> : definitions?.length === 0 && <section className="card empty-state"><h2>尚無核准參數</h2><p>目前沒有可建立的業務參數鍵。參數用途、型別與範圍須先經核准並納入定義，不能在此任意新增鍵值。</p></section>}
    <div className={`adm-grid ${!definitions?.length ? 'read-only-grid' : ''}`}>
      <section className="card" aria-labelledby="parameters-heading"><h2 id="parameters-heading">參數列表</h2>{loading ? <p role="status">正在載入參數…</p> : loadError ? <p>無法載入參數，請重新載入。</p> : <><div className="table-scroll"><table><caption className="sr-only">核准系統參數列表</caption><thead><tr><th scope="col">參數鍵／名稱</th><th scope="col">型別／值</th><th scope="col">狀態</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((parameter) => <tr key={parameter.id}><th scope="row">{parameter.name}<small>{parameter.key}</small></th><td><small>{parameter.valueType}</small><code className="parameter-value">{parameter.valueText}</code></td><td>{parameter.enabled ? '啟用' : '停用'}</td><td>{definitions?.some((item) => item.key === parameter.key && item.valueType === parameter.valueType) ? <button className="text-button" disabled={busy} aria-label={`編輯參數 ${parameter.key}`} onClick={() => { setEditing(parameter); setKey(parameter.key); setName(parameter.name); setValueText(parameter.valueText); setDescription(parameter.description); setEnabled(parameter.enabled); setError(null); setNotice('') }}>編輯</button> : <span className="readonly-tag">無核准定義</span>}</td></tr>)}</tbody></table></div>{data?.content.length === 0 && <p className="empty-state">尚無系統參數資料。</p>}{data && <Pager label="系統參數分頁" {...data} disabled={busy} onPage={(value) => { clear(); setError(null); setPage(value) }} />}</>}</section>
      {Boolean(definitions?.length) && <section className="card" aria-labelledby="parameter-form-heading"><h2 id="parameter-form-heading">{editing ? '編輯系統參數' : '新增核准參數'}</h2><form className="stack-form" onSubmit={(event) => { void submit(event) }}><label htmlFor="parameter-key">核准參數鍵</label><select id="parameter-key" required disabled={busy || Boolean(editing)} value={key} onChange={(event) => choose(event.target.value)}><option value="">請選擇核准參數</option>{definitions?.map((item) => <option value={item.key} key={item.key}>{item.key} — {item.name}</option>)}</select>
        {definition && <><p className="definition-note">型別：<strong>{definition.valueType}</strong>。鍵與型別建立後不可修改。</p><p className="definition-note">{definition.description}</p><label htmlFor="parameter-name">參數名稱</label><input id="parameter-name" required disabled={busy} value={name} onChange={(event) => setName(event.target.value)} /><label htmlFor="parameter-value">參數值</label>{definition.valueType === 'BOOLEAN' ? <select id="parameter-value" disabled={busy} value={valueText} onChange={(event) => setValueText(event.target.value)}><option value="false">false</option><option value="true">true</option></select> : definition.allowedValues.length > 0 ? <select id="parameter-value" disabled={busy} value={valueText} required={!definition.allowedValues.includes('')} onChange={(event) => setValueText(event.target.value)}>{!definition.allowedValues.includes('') && <option value="">請選擇核准值</option>}{definition.allowedValues.map((value) => <option value={value} key={value}>{value === '' ? '（空字串）' : value}</option>)}</select> : <input id="parameter-value" disabled={busy} inputMode={definition.valueType === 'NUMBER' ? 'decimal' : undefined} maxLength={4000} value={valueText} onChange={(event) => setValueText(event.target.value)} aria-describedby="parameter-bounds" />}
          <small id="parameter-bounds">{definition.valueType === 'NUMBER' ? `十進位文字；最小 ${definition.minValue ?? '未限制'}，最大 ${definition.maxValue ?? '未限制'}，小數位 ${definition.decimalScale ?? '未限制'}。` : definition.valueType === 'TEXT' ? `文字長度 ${definition.minLength ?? 0}–${definition.maxLength ?? 2000} 字元。` : '布林值僅接受 true 或 false。'}</small><label htmlFor="parameter-description">參數說明</label><textarea id="parameter-description" rows={3} disabled={busy} value={description} onChange={(event) => setDescription(event.target.value)} /><label className="checkbox-label"><input type="checkbox" disabled={busy} checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />啟用參數</label></>}
        <div className="form-actions"><button className="primary-button" disabled={busy || loading || Boolean(loadError) || catalogLoading || !definition}>{busy ? '儲存中…' : editing ? '儲存參數' : '建立參數'}</button>{editing && <button className="secondary-button" type="button" disabled={busy} onClick={clear}>取消參數編輯</button>}</div></form></section>}
    </div>
  </div>
}
