import { useEffect, useRef, useState } from 'react'
import { getExceptionEvidence, getExceptionEvidenceContent } from './exceptionApi'
import type { ExceptionPage, SupplementEvidence } from './exceptionApi'
import { exceptionPhaseLabels, safeExceptionExit } from './exceptionModel'
import { ExceptionErrorNotice } from './ExceptionShared'
export default function ExceptionPhotos({ id, disabled, onExpired }: { id: string; disabled: boolean; onExpired: () => void }) {
  const [page, setPage] = useState(0), [revision, setRevision] = useState(0), [state, setState] = useState<{ key: string; data?: ExceptionPage<SupplementEvidence>; error?: unknown }>(), [selected, setSelected] = useState<SupplementEvidence | null>(null), [preview, setPreview] = useState<{ key: string; url?: string; error?: unknown }>()
  const region = useRef<HTMLElement>(null), key = `${id}/${page}/${revision}`, photoKey = selected ? `${id}/${selected.evidence.id}` : ''
  useEffect(() => {
    if (disabled) return
    const controller = new AbortController(); let active = true
    void getExceptionEvidence(id, { page, size: 5 }, controller.signal).then((data) => { if (active) setState({ key, data }) }).catch((error: unknown) => { if (active) { setState({ key, error }); if (safeExceptionExit(error)) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [id, page, key, disabled, onExpired])
  useEffect(() => {
    if (!selected) return
    const controller = new AbortController(); let active = true, url: string | undefined
    void getExceptionEvidenceContent(id, selected.evidence, controller.signal).then((blob) => { if (active) { url = URL.createObjectURL(blob); setPreview({ key: photoKey, url }) } }).catch((error: unknown) => { if (active) { setPreview({ key: photoKey, error }); if (safeExceptionExit(error)) onExpired() } })
    return () => { active = false; controller.abort(); if (url) URL.revokeObjectURL(url) }
  }, [id, selected, photoKey, onExpired])
  useEffect(() => { if (selected) region.current?.focus() }, [selected])
  const current = state?.key === key ? state : undefined, image = preview?.key === photoKey ? preview : undefined
  return <section className="card exception-photos" aria-labelledby="exception-photos-heading"><div className="exception-section-heading"><h2 id="exception-photos-heading">補件照片</h2><button type="button" className="text-button" disabled={disabled} onClick={() => setRevision((value) => value + 1)}>重新讀取補件照片</button></div><p className="exception-muted">只列出與此異常明確關聯的照片。逐次確認私有讀取權限；照片不會保存至瀏覽器儲存空間。</p>{!current ? <p role="status">正在讀取補件照片…</p> : current.error ? <ExceptionErrorNotice error={current.error} /> : <>{current.data?.content.length === 0 ? <p className="empty-state">尚無關聯的補件照片。</p> : <ul className="exception-photo-list">{current.data?.content.map((row, index) => <li key={row.evidence.id}><h3>補件照片 {page * 5 + index + 1}</h3><span className="exception-photo-state">{row.submitted ? '已提交補件' : '已上傳，尚未提交'}</span><p className="exception-muted">{exceptionPhaseLabels[row.evidence.phase]} · 上傳時執行版本 {row.evidence.executionVersion}<br /><time dateTime={row.evidence.uploadedAt}>{row.evidence.uploadedAt}</time> · {row.evidence.byteSize} bytes</p><button type="button" className="secondary-button" disabled={disabled} onClick={() => setSelected(row)}>查看補件照片 {page * 5 + index + 1}</button><details className="exception-identity"><summary>照片與補件識別</summary><p>補件要求：{row.requestId}</p><p>照片：{row.evidence.id}</p><p>SHA-256：{row.evidence.sha256}</p></details></li>)}</ul>}{current.data && <nav className="exception-pager" aria-label="補件照片分頁"><span>共 {current.data.totalElements} 張</span><div><button type="button" className="secondary-button" disabled={disabled || page === 0} onClick={() => { setSelected(null); setPage(page - 1) }}>上一頁補件照片</button><button type="button" className="secondary-button" disabled={disabled || page >= 100000 || page + 1 >= current.data.totalPages} onClick={() => { setSelected(null); setPage(page + 1) }}>下一頁補件照片</button></div></nav>}</>}{selected && <section ref={region} tabIndex={-1} className="exception-photo-preview" aria-label="補件照片預覽"><div className="exception-section-heading"><h3>補件照片預覽</h3><button type="button" className="secondary-button" onClick={() => setSelected(null)}>關閉照片預覽</button></div>{!image ? <p role="status">正在確認並讀取私有照片…</p> : image.error ? <ExceptionErrorNotice error={image.error} /> : image.url && <img src={image.url} alt="異常補件照片" />}</section>}</section>
}
