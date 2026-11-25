import { useEffect, useState } from 'react'
import { ApiError } from './api'
import { ErrorNotice } from './AuthForms'
import { getDriverMe } from './driverApi'
import type { DriverSelf as SelfData } from './driverApi'
import { dutyLabels, employmentLabels, licenseDateHint } from './driverModel'
import './driver.css'

export default function DriverSelf({ onExpired }: { onExpired: () => void }) {
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState<{ revision: number; data?: SelfData; error?: unknown }>()
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void getDriverMe(controller.signal).then((data) => { if (active) setState({ revision, data }) }).catch((error: unknown) => { if (active) { setState({ revision, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [revision, onExpired])
  const current = state?.revision === revision ? state : undefined
  const driver = current?.data
  const unavailable = current?.error instanceof ApiError && [403, 404].includes(current.error.status)
  const hint = driver ? licenseDateHint(driver.licenseDueDate) : null
  return <section className="driver-workspace driver-self"><div className="section-title driver-heading"><div><p className="eyebrow">MY DRIVER PROFILE</p><h1>本人司機資料</h1><p className="page-description">由目前登入帳號的有效綁定取得本人必要資料；此頁僅供檢視。</p></div><button className="secondary-button" disabled={!current} onClick={() => setRevision((value) => value + 1)}>重新讀取本人資料</button></div>{!current ? <section className="card" role="status">正在讀取本人司機資料…</section> : current.error ? <section className="card"><ErrorNotice error={current.error} /><h2>{unavailable ? '目前沒有可顯示的本人司機資料' : '無法讀取本人司機資料'}</h2><p>{unavailable ? '請聯絡管理員確認帳號綁定及存取狀態。此頁不提供其他司機查詢。' : '請確認連線後重新讀取；舊資料已隱去。'}</p></section> : driver && <section className="card driver-self-card" aria-labelledby="driver-self-name"><div className="driver-self-heading"><div><p className="eyebrow">DRIVER PROFILE</p><h2 id="driver-self-name">{driver.name}</h2><p className="driver-muted">司機編號 {driver.driverCode}</p></div><span className={`driver-status driver-status-${driver.employmentStatus}`}>{employmentLabels[driver.employmentStatus]}</span></div><dl className="driver-self-data"><div><dt>電話</dt><dd>{driver.phone ?? '未提供'}</dd></div><div><dt>電子郵件</dt><dd>{driver.email ?? '未提供'}</dd></div><div><dt>在職狀態</dt><dd>{employmentLabels[driver.employmentStatus]}</dd></div><div><dt>出勤狀態</dt><dd>{dutyLabels[driver.dutyStatus]}</dd></div><div><dt>駕照類別</dt><dd>{driver.licenseTypeLabel ?? '未提供'}{driver.licenseType && !driver.licenseTypeEnabled && <small>原類別已封存</small>}</dd></div><div><dt>證照記錄期限</dt><dd>{driver.licenseDueDate ? <time dateTime={driver.licenseDueDate}>{driver.licenseDueDate}</time> : '未提供'}{hint && <small>{hint}</small>}</dd></div></dl><p className="driver-history-note">在職與出勤是行政記錄，不代表使用中、可派工或法規資格。若需更正資料，請聯絡管理員；本人頁不提供修改或出勤切換。</p></section>}</section>
}
