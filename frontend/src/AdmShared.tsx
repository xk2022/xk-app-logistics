import { ApiError } from './api'
import { ErrorNotice } from './AuthForms'

export function AdmError({ error }: { error: unknown }) {
  return <><ErrorNotice error={error} />{error instanceof ApiError && error.status === 409 && <p className="conflict-help">資料版本或代碼衝突。請重新載入最新資料，再確認並操作；系統不會自動重送變更。</p>}</>
}
export function Pager({ label, page, totalPages, totalElements, disabled, onPage }: { label: string; page: number; totalPages: number; totalElements: number; disabled: boolean; onPage: (value: number) => void }) {
  return <nav className="adm-pager" aria-label={label}><span>共 {totalElements} 筆 · {totalPages ? `第 ${page + 1} / ${totalPages} 頁` : '目前無資料'}</span><div><button className="secondary-button compact" disabled={disabled || page === 0} onClick={() => onPage(page - 1)}>上一頁</button><button className="secondary-button compact" disabled={disabled || page + 1 >= totalPages} onClick={() => onPage(page + 1)}>下一頁</button></div></nav>
}
