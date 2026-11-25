import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { createCategory, createItem, getCategories, getItems, getOptions, updateCategory, updateItem } from './admApi'
import type { Category, Item } from './admApi'
import { AdmError, Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'

function ItemManager({ category, onExpired }: { category: Category; onExpired: () => void }) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const load = useCallback((page: number, signal?: AbortSignal) => getItems(category.id, page, signal), [category.id])
  const { loading, data, error: loadError } = useAdmPage(load, page, revision, onExpired)
  const [options, setOptions] = useState<Item[] | null>(null)
  const [optionsError, setOptionsError] = useState<unknown>(null)
  const [editing, setEditing] = useState<Item | null>(null)
  const [code, setCode] = useState('')
  const [label, setLabel] = useState('')
  const [sortOrder, setSortOrder] = useState('0')
  const [enabled, setEnabled] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const readOnly = category.readOnly || category.code === 'ORDER_TYPE'
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    void getOptions(category.code, controller.signal).then((values) => { if (active) { setOptions(values); setOptionsError(null) } })
      .catch((failure: unknown) => { if (active) { setOptions(null); setOptionsError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [category.code, category.enabled, revision, onExpired])
  const clear = () => { setEditing(null); setCode(''); setLabel(''); setSortOrder('0'); setEnabled(true) }
  const reload = () => { clear(); setError(null); setOptions(null); setOptionsError(null); setRevision((value) => value + 1) }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (readOnly) return
    setBusy(true); setError(null); setNotice('')
    try {
      const order = Number(sortOrder)
      if (!sortOrder.trim() || !Number.isInteger(order) || order < 0 || order > 1000000) throw new ApiError(400, '排序須為 0–1000000 的整數。')
      if (editing) await updateItem(editing.id, { label, sortOrder: order, enabled, version: editing.version })
      else await createItem(category.id, { code, label, sortOrder: order, enabled })
      setNotice(editing ? '字典項目已更新。' : '字典項目已建立。'); clear(); setOptions(null); setRevision((value) => value + 1)
    } catch (failure) { setError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() }
    finally { setBusy(false) }
  }
  return <section className="adm-item-section" aria-labelledby="items-heading">
    <div className="section-title"><div><p className="eyebrow">DICTIONARY ITEMS / {category.code}</p><h2 id="items-heading">字典項目：{category.name}</h2></div><button className="secondary-button" disabled={busy || loading} onClick={reload}>重新載入項目</button></div>
    {readOnly && <p className="scope-strip">此分類與 IMPORT／EXPORT／LOCAL 為固定業務枚舉，僅供唯讀對照，無法在 ADM 新增、編輯或停用。</p>}
    {!category.enabled && <p className="warning-notice">此分類已停用。歷史項目仍可檢視與管理，但全部項目都不可供新引用。</p>}
    {notice && <p className="success-notice" role="status">{notice}</p>}<AdmError error={error ?? loadError} />
    <div className={`adm-grid ${readOnly ? 'read-only-grid' : ''}`}>
      <div className="card"><h3>項目列表</h3>{loading ? <p role="status">正在載入項目…</p> : loadError ? <p>無法載入項目，請重新載入。</p> : <><div className="table-scroll"><table><caption className="sr-only">{category.name}項目</caption><thead><tr><th scope="col">代碼／標籤</th><th scope="col">排序</th><th scope="col">項目狀態</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((item) => <tr key={item.id}><th scope="row">{item.label}<small>{item.code}</small></th><td>{item.sortOrder}</td><td>{item.enabled ? '啟用' : '停用'}{!category.enabled && <small>分類停用中</small>}</td><td>{readOnly || item.readOnly ? <span className="readonly-tag">固定唯讀</span> : <button className="text-button" disabled={busy} aria-label={`編輯項目 ${item.code}`} onClick={() => { setEditing(item); setCode(item.code); setLabel(item.label); setSortOrder(String(item.sortOrder)); setEnabled(item.enabled); setError(null); setNotice('') }}>編輯</button>}</td></tr>)}</tbody></table></div>{data?.content.length === 0 && <p className="empty-state">尚無字典項目，正式值請依核准需求建立。</p>}{data && <Pager label="字典項目分頁" {...data} disabled={busy} onPage={(value) => { clear(); setError(null); setPage(value) }} />}</>}
        <div className="options-preview"><h3>可供新引用的選項</h3><AdmError error={optionsError} />{options === null && !optionsError ? <p role="status">正在檢查可用選項…</p> : options?.length ? <ul>{options.map((item) => <li key={item.id}>{item.label} <code>{item.code}</code></li>)}</ul> : !optionsError && <p>{category.enabled ? '目前沒有啟用的可用選項。' : '分類已停用，可用選項為空。'}</p>}</div>
      </div>
      {!readOnly && <section className="card" aria-labelledby="item-form-heading"><h3 id="item-form-heading">{editing ? '編輯字典項目' : '新增字典項目'}</h3><form className="stack-form" onSubmit={(event) => { void submit(event) }}><label htmlFor="item-code">項目代碼</label><input id="item-code" required disabled={busy} readOnly={Boolean(editing)} value={code} onChange={(event) => setCode(event.target.value)} aria-describedby="item-code-help" /><small id="item-code-help">代碼建立後不可修改。</small><label htmlFor="item-label">項目標籤</label><input id="item-label" required disabled={busy} value={label} onChange={(event) => setLabel(event.target.value)} /><label htmlFor="item-order">項目排序</label><input id="item-order" type="number" min="0" max="1000000" step="1" required disabled={busy} value={sortOrder} onChange={(event) => setSortOrder(event.target.value)} /><label className="checkbox-label"><input type="checkbox" disabled={busy} checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />啟用項目</label><div className="form-actions"><button className="primary-button" disabled={busy || loading || Boolean(loadError)}>{busy ? '儲存中…' : editing ? '儲存項目' : '建立項目'}</button>{editing && <button className="secondary-button" type="button" disabled={busy} onClick={clear}>取消項目編輯</button>}</div></form></section>}
    </div>
  </section>
}

export default function DictionaryManager({ onExpired }: { onExpired: () => void }) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const { loading, data, error: loadError } = useAdmPage(getCategories, page, revision, onExpired)
  const [selected, setSelected] = useState<Category | null>(null)
  const [editing, setEditing] = useState<Category | null>(null)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [sortOrder, setSortOrder] = useState('0')
  const [enabled, setEnabled] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const clear = () => { setEditing(null); setCode(''); setName(''); setSortOrder('0'); setEnabled(true) }
  const reload = () => { clear(); setSelected(null); setError(null); setRevision((value) => value + 1) }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (editing?.readOnly || editing?.code === 'ORDER_TYPE') return
    setBusy(true); setError(null); setNotice('')
    try {
      const order = Number(sortOrder)
      if (!sortOrder.trim() || !Number.isInteger(order) || order < 0 || order > 1000000) throw new ApiError(400, '排序須為 0–1000000 的整數。')
      const updated = editing ? await updateCategory(editing.id, { name, enabled, sortOrder: order, version: editing.version }) : await createCategory({ code, name, enabled, sortOrder: order })
      if (selected?.id === updated.id) setSelected(updated)
      setNotice(editing ? '字典分類已更新。' : '字典分類已建立。'); clear(); setRevision((value) => value + 1)
    } catch (failure) { setError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() }
    finally { setBusy(false) }
  }
  return <div>
    <div className="section-title"><div><h1>字典管理</h1><p className="page-description">分類與項目使用穩定代碼。停用保留歷史，沒有刪除操作。</p></div><button className="secondary-button" disabled={busy || loading} onClick={reload}>重新載入分類</button></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}<AdmError error={error ?? loadError} />
    <div className="adm-grid"><section className="card" aria-labelledby="categories-heading"><h2 id="categories-heading">字典分類</h2>{loading ? <p role="status">正在載入分類…</p> : loadError ? <p>無法載入分類，請重新載入。</p> : <><div className="table-scroll"><table><caption className="sr-only">字典分類列表</caption><thead><tr><th scope="col">代碼／名稱</th><th scope="col">排序</th><th scope="col">狀態</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((category) => <tr key={category.id}><th scope="row">{category.name}<small>{category.code}</small></th><td>{category.sortOrder}</td><td>{category.enabled ? '啟用' : '停用'}</td><td><button className="text-button" disabled={busy} aria-label={`管理項目 ${category.code}`} onClick={() => setSelected(category)}>項目</button>{category.readOnly || category.code === 'ORDER_TYPE' ? <span className="readonly-tag">固定唯讀</span> : <button className="text-button" disabled={busy} aria-label={`編輯分類 ${category.code}`} onClick={() => { setEditing(category); setCode(category.code); setName(category.name); setSortOrder(String(category.sortOrder)); setEnabled(category.enabled); setError(null); setNotice('') }}>編輯</button>}</td></tr>)}</tbody></table></div>{data?.content.length === 0 && <p className="empty-state">尚無字典分類。</p>}{data && <Pager label="字典分類分頁" {...data} disabled={busy} onPage={(value) => { clear(); setSelected(null); setError(null); setPage(value) }} />}</>}</section>
      <section className="card" aria-labelledby="category-form-heading"><h2 id="category-form-heading">{editing ? '編輯字典分類' : '新增字典分類'}</h2><form className="stack-form" onSubmit={(event) => { void submit(event) }}><label htmlFor="category-code">分類代碼</label><input id="category-code" required readOnly={Boolean(editing)} disabled={busy} value={code} onChange={(event) => setCode(event.target.value)} aria-describedby="category-code-help" /><small id="category-code-help">代碼建立後不可修改，不得取代固定業務枚舉。</small><label htmlFor="category-name">分類名稱</label><input id="category-name" required disabled={busy} value={name} onChange={(event) => setName(event.target.value)} /><label htmlFor="category-order">分類排序</label><input id="category-order" type="number" min="0" max="1000000" step="1" required disabled={busy} value={sortOrder} onChange={(event) => setSortOrder(event.target.value)} /><label className="checkbox-label"><input type="checkbox" disabled={busy} checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />啟用分類</label><div className="form-actions"><button className="primary-button" disabled={busy || loading || Boolean(loadError)}>{busy ? '儲存中…' : editing ? '儲存分類' : '建立分類'}</button>{editing && <button className="secondary-button" type="button" disabled={busy} onClick={clear}>取消分類編輯</button>}</div></form></section>
    </div>
    {selected ? <ItemManager key={`${selected.id}/${selected.version}`} category={selected} onExpired={onExpired} /> : <p className="scope-strip">選擇分類的「項目」，即可檢視及管理該分類的字典項目。</p>}
  </div>
}
