import { useEffect, useRef, useState } from 'react'
import { getDriverEvidenceContent } from './driverExecutionApi'
import type { DriverEvidence } from './driverExecutionApi'
import { DriverErrorNotice } from './DriverShared'
interface Props { assignmentId: string; photo: DriverEvidence; readEpoch: number; disabled: boolean; onClose: () => void; onError: (error: unknown, epoch: number) => void }
export default function DriverPrivatePreview(props: Props) {
  // Suspending unmounts the private read, so a revoked URL cannot reappear on resume.
  return props.disabled ? null : <PrivateRead key={`${props.assignmentId}/${props.photo.id}/${props.readEpoch}`} {...props} />
}
function PrivateRead({ assignmentId, photo, readEpoch, onClose, onError }: Props) {
  const [state, setState] = useState<{url?:string;error?:unknown}>(), region = useRef<HTMLElement>(null)
  useEffect(() => { const controller = new AbortController(); let active = true, url: string | undefined; void getDriverEvidenceContent(assignmentId, photo, controller.signal).then((blob) => { if (active && !controller.signal.aborted) { url = URL.createObjectURL(blob); setState({ url }) } }).catch((error: unknown) => { if (active && !controller.signal.aborted) { setState({ error }); onError(error, readEpoch) } }); return () => { active = false; controller.abort(); if (url) URL.revokeObjectURL(url) } }, [assignmentId, photo, readEpoch, onError])
  useEffect(() => { region.current?.focus() }, [])
  return <section ref={region} tabIndex={-1} className="driver-private-preview" aria-label="私有照片預覽"><div className="driver-section-heading"><h3>私有照片預覽</h3><button type="button" className="secondary-button" onClick={onClose}>關閉私有照片預覽</button></div>{!state ? <p role="status">正在確認並讀取本人私有照片…</p> : state.error ? <DriverErrorNotice error={state.error} /> : state.url && <img src={state.url} alt="本人私有任務照片" />}</section>
}
