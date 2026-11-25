import { useState } from 'react'
import DictionaryManager from './DictionaryManager'
import ParameterManager from './ParameterManager'
import ChangeLogViewer from './ChangeLogViewer'

type AdmPage = 'dictionaries' | 'parameters' | 'changeLogs'
export default function AdmManager({ onExpired }: { onExpired: () => void }) {
  const [page, setPage] = useState<AdmPage>('dictionaries')
  return <section className="adm-manager"><p className="eyebrow">APPLICATION DATA MANAGEMENT / ADMIN</p><nav className="adm-navigation" aria-label="系統管理選單">{([['dictionaries', '字典管理'], ['parameters', '系統參數'], ['changeLogs', '異動紀錄']] as const).map(([value, label]) => <button className={`secondary-button ${page === value ? 'selected' : ''}`} key={value} aria-current={page === value ? 'page' : undefined} onClick={() => setPage(value)}>{label}</button>)}</nav>{page === 'dictionaries' ? <DictionaryManager onExpired={onExpired} /> : page === 'parameters' ? <ParameterManager onExpired={onExpired} /> : <ChangeLogViewer onExpired={onExpired} />}</section>
}
