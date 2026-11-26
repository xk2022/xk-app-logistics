import { useEffect, useRef, useState } from 'react'
import { TmsApiError, assignTask, reassignDispatch, revokeDispatch } from './tmsApi'
import type { DispatchDetail, ResourceCandidate, ResourcePosition, TmsWriteResult } from './tmsApi'
import { actionLabels, candidateSelectable, commandResources, immutableSnapshot, makeDispatchCommand, safeExitRequired, settlesPriorDispatch, sourceAllowsNewDispatch } from './tmsModel'
import type { CommandMode, DispatchCommand, PendingDispatchCommand } from './tmsModel'
import DispatchResourcePicker from './DispatchResourcePicker'

const positions: ResourcePosition[] = ['DRIVER', 'MAIN', 'TRAILER']
export interface DispatchCommandFormProps { detail: DispatchDetail; disabled?: boolean; stale?: boolean; onExpired: () => void; onCompleted: (result: TmsWriteResult) => void; onDirtyChange: (dirty: boolean) => void; onBusyChange: (busy: boolean) => void; onPendingChange: (pending: boolean) => void }
export default function DispatchCommandForm({ detail, disabled = false, stale = false, onExpired, onCompleted, onDirtyChange, onBusyChange, onPendingChange }: DispatchCommandFormProps) {
  const [mode, setMode] = useState<CommandMode | null>(null)
  const initial = detail.current ? commandResources(detail.current) : { driverId: '', mainVehicleId: '', trailerId: '' }
  const [selection, setSelection] = useState({ ...initial, reason: '' })
  const [candidates, setCandidates] = useState<Partial<Record<ResourcePosition, ResourceCandidate | null>>>({})
  const [pending, setPending] = useState<PendingDispatchCommand | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [conflict, setConflict] = useState(false)
  const [confirmation, setConfirmation] = useState<DispatchCommand | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const active = useRef(true)
  const callbacks = useRef({ onDirtyChange, onBusyChange, onPendingChange })
  const confirmButton = useRef<HTMLButtonElement>(null)
  const dirty = mode !== null || pending !== null || busy || conflict
  const locked = disabled || busy || pending !== null
  const fields = error instanceof TmsApiError ? error.fields : {}
  useEffect(() => { callbacks.current = { onDirtyChange, onBusyChange, onPendingChange } }, [onDirtyChange, onBusyChange, onPendingChange])
  useEffect(() => { active.current = true; return () => { active.current = false; callbacks.current.onDirtyChange(false); callbacks.current.onBusyChange(false); callbacks.current.onPendingChange(false) } }, [])
  useEffect(() => { callbacks.current.onDirtyChange(dirty) }, [dirty])
  useEffect(() => { callbacks.current.onPendingChange(pending !== null) }, [pending])
  useEffect(() => { if (confirmation) confirmButton.current?.focus() }, [confirmation])
  useEffect(() => { if (!dirty) return; const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn) }, [dirty])
  const current = detail.current
  const canRevoke = current?.status === 'ASSIGNED'
  const canAssign = current === null && sourceAllowsNewDispatch(detail.task)
  const canReassign = canRevoke && sourceAllowsNewDispatch(detail.task, true)
  const start = (next: CommandMode) => {
    if (locked || conflict || stale || mode !== null || next === 'assign' && !canAssign || next === 'revoke' && !canRevoke || next === 'reassign' && !canReassign) return
    setMode(next); setSelection({ ...initial, reason: '' }); setCandidates({}); setError(null); setConfirmation(null); setDiscarding(false)
  }
  const changed = (patch: Partial<typeof selection>) => { if (!locked) { setSelection((previous) => ({ ...previous, ...patch })); setError(null); setConfirmation(null) } }
  const verifyCandidates = () => {
    if (mode === 'revoke') return
    const problems: Record<string, string> = {}
    for (const position of positions) {
      const name = position === 'DRIVER' ? 'driverId' : position === 'MAIN' ? 'mainVehicleId' : 'trailerId'
      const id = selection[name], candidate = candidates[position]
      if (id && candidate && !candidateSelectable(candidate, detail.task.taskId, mode === 'reassign' ? current?.id : undefined)) problems[name] = '此候選目前被阻擋，請查詢並確認可用來源。'
      if (id && !candidate && id !== initial[name]) problems[name] = '已選來源尚未確認，請重新查詢並明確選取。'
    }
    if (Object.keys(problems).length) throw new TmsApiError(400, 'E0400', '請確認資源候選。', { reason: 'VALIDATION_ERROR', fields: problems })
  }
  const review = () => {
    if (!mode || locked || conflict || stale) return
    try { verifyCandidates(); setConfirmation(immutableSnapshot(makeDispatchCommand(mode, detail, selection))); setError(null) }
    catch (failure) { setError(failure) }
  }
  const send = async (operation: PendingDispatchCommand) => {
    setBusy(true); callbacks.current.onBusyChange(true); setPending(operation); callbacks.current.onPendingChange(true); setError(null); setConfirmation(null); setDiscarding(false)
    try {
      const result = operation.kind === 'assign' ? await assignTask(operation.taskId, operation.body, operation.key)
        : operation.kind === 'revoke' ? await revokeDispatch(operation.assignmentId, operation.body, operation.key, { taskId: operation.taskId })
          : await reassignDispatch(operation.assignmentId, operation.body, operation.key, { taskId: operation.taskId })
      if (!active.current) return
      setPending(null); setMode(null); setConflict(false); callbacks.current.onPendingChange(false); callbacks.current.onDirtyChange(false); onCompleted(result)
    } catch (failure) {
      if (!active.current) return
      if (safeExitRequired(failure)) { setPending(null); callbacks.current.onPendingChange(false); onExpired(); return }
      setError(failure)
      const uncertain = !(failure instanceof TmsApiError) || failure.uncertain || failure.reason === 'CSRF_INVALID' || ['E1607', 'E1608'].includes(failure.code)
      const unresolved = operation.unresolved && !settlesPriorDispatch(failure)
      setPending(uncertain || unresolved ? { ...operation, unresolved: true } : null)
      if (!uncertain && !unresolved && failure instanceof TmsApiError && ['E1601', 'E1603'].includes(failure.code)) setConflict(true)
    } finally { if (active.current) { setBusy(false); callbacks.current.onBusyChange(false) } }
  }
  const confirm = () => {
    if (!confirmation || locked || conflict || stale) return
    void send(immutableSnapshot({ ...confirmation, key: crypto.randomUUID() }))
  }
  const cancel = () => { if (locked) return; setMode(null); setSelection({ ...initial, reason: '' }); setCandidates({}); setError(null); setConfirmation(null); setDiscarding(false) }
  const keyReused = error instanceof TmsApiError && error.code === 'E1607'
  return <section className="dispatch-command" aria-labelledby="dispatch-command-heading"><h2 id="dispatch-command-heading">派工操作</h2><p className="dispatch-muted">操作使用任務派工版本 {detail.task.generation}，不是派工記錄的 0／1 版本。每個組合都由伺服器重新核對。</p><div className="dispatch-actions">{current === null ? <button type="button" className="primary-button" disabled={locked || conflict || stale || mode !== null || !canAssign} onClick={() => start('assign')}>指派任務</button> : <><button type="button" className="primary-button" disabled={locked || conflict || stale || mode !== null || !canReassign} onClick={() => start('reassign')}>改派任務</button><button type="button" className="secondary-button" disabled={locked || conflict || stale || mode !== null || !canRevoke} onClick={() => start('revoke')}>撤派任務</button></>}</div>
    {Boolean(error) && <div className="error-notice" role="alert"><p>{error instanceof Error ? error.message : '操作未完成，請重新確認。'}</p>{error instanceof TmsApiError && <><p>錯誤代碼：{error.code}{error.actualGeneration !== null && error.actualGeneration !== undefined ? ` · 目前任務版本 ${error.actualGeneration}` : ''}</p>{Object.keys(fields).length > 0 && <ul>{Object.entries(fields).map(([name, message]) => <li key={name}>{message}</li>)}</ul>}</>}</div>}
    {conflict && <div className="dispatch-warning" role="alert"><h3>派工版本或狀態已變更，本機內容已保留</h3><p>不會自動採用回傳版本或重送。請比較並明確重新讀取目前派工，再決定下一步。</p></div>}
    {pending && !busy && <section className="dispatch-warning" role="status"><h3>{keyReused ? '重送識別不一致，停止重送' : '操作結果尚未確認'}</h3><p>原請求內容與識別保持凍結；先前操作可能已成功。不能換資源、原因、版本或新識別再次送出。明確離開會清除此頁的重試資訊。</p>{!keyReused && <button type="button" className="primary-button" disabled={disabled} onClick={() => { if (!disabled) void send(pending) }}>以原請求重試操作</button>}</section>}
    {mode && <form className="dispatch-operation-form" aria-label={`${actionLabels[mode]}操作表單`} onSubmit={(event) => { event.preventDefault(); review() }}><h3>{actionLabels[mode]}操作內容</h3>{mode !== 'revoke' && <div className="dispatch-resource-grid">{positions.map((position) => {
      const name = position === 'DRIVER' ? 'driverId' : position === 'MAIN' ? 'mainVehicleId' : 'trailerId'
      const initialSource = position === 'DRIVER' ? current?.driver : position === 'MAIN' ? current?.mainVehicle : current?.trailer
      const initialChoice = initialSource ? position === 'DRIVER' ? { id: initialSource.id, code: current!.driver.driverCode, label: current!.driver.name } : { id: initialSource.id, code: position === 'MAIN' ? current!.mainVehicle.plateNo : current!.trailer!.plateNo, label: position === 'MAIN' ? current!.mainVehicle.vehicleTypeLabel : current!.trailer!.vehicleTypeLabel } : undefined
      return <DispatchResourcePicker key={`${mode}/${position}`} taskId={detail.task.taskId} assignmentId={mode === 'reassign' ? current?.id : undefined} position={position} value={selection[name]} initialChoice={initialChoice} disabled={locked || Boolean(confirmation)} onExpired={onExpired} issue={fields[name]} onSelect={(id, candidate) => { changed({ [name]: id }); setCandidates((previous) => ({ ...previous, [position]: candidate })) }} onCandidateState={(id, candidate) => { if (id === selection[name]) { setCandidates((previous) => previous[position] === candidate ? previous : { ...previous, [position]: candidate }); if (candidate && !candidateSelectable(candidate, detail.task.taskId, mode === 'reassign' ? current?.id : undefined)) setConfirmation(null) } }} />
    })}</div>}{mode !== 'assign' && <div className="dispatch-field"><label htmlFor="dispatch-command-reason">{actionLabels[mode]}原因</label><textarea id="dispatch-command-reason" name="reason" rows={4} value={selection.reason} disabled={locked || Boolean(confirmation)} onChange={(event) => changed({ reason: event.target.value })} aria-invalid={fields.reason ? true : undefined} aria-describedby="dispatch-reason-help" /><small id="dispatch-reason-help">正規化後 1–500 字元，可含換行與 Tab。這是本次操作的新原因，不會改寫歷史。</small>{fields.reason && <p className="dispatch-field-error">{fields.reason}</p>}</div>}
      <div className="dispatch-actions"><button type="submit" className="primary-button" disabled={locked || conflict || stale || Boolean(confirmation)}>檢視{actionLabels[mode]}確認</button><button type="button" className="secondary-button" disabled={locked} onClick={() => setDiscarding(true)}>取消派工編輯</button></div>{discarding && <div className="dispatch-warning" role="group" aria-label="確認捨棄派工編輯"><p>確認捨棄本機選擇與原因？沒有送出的內容不會套用。</p><button type="button" className="secondary-button" disabled={locked} onClick={cancel}>捨棄本機操作</button><button type="button" className="text-button" onClick={() => setDiscarding(false)}>保留操作內容</button></div>}
      {confirmation && <section className="dispatch-confirm" role="group" aria-label={`${actionLabels[mode]}確認`}><h3>確認{actionLabels[mode]}</h3><dl className="dispatch-data-grid"><div><dt>任務</dt><dd>{detail.task.taskNo}</dd></div><div><dt>任務派工版本</dt><dd>{confirmation.body.expectedGeneration}</dd></div>{mode !== 'revoke' && <><div><dt>司機</dt><dd>{candidates.DRIVER?.code ?? current?.driver.driverCode ?? '已選司機'}</dd></div><div><dt>主車</dt><dd>{candidates.MAIN?.code ?? current?.mainVehicle.plateNo ?? '已選主車'}</dd></div><div><dt>拖板</dt><dd>{selection.trailerId ? candidates.TRAILER?.code ?? current?.trailer?.plateNo ?? '已選拖板' : '未使用拖板'}</dd></div></>}{mode !== 'assign' && <div><dt>本次原因</dt><dd className="dispatch-raw">{selection.reason}</dd></div>}</dl><p>{mode === 'revoke' ? '確認後釋放此派工的占用並保留歷史；不會把撤派視為任務完成或前置就緒。' : '確認後立即建立排他占用。候選不保證完整組合可派，伺服器會在同一交易重新驗證；合成測試不代表正式營運授權。'}</p><div className="dispatch-actions"><button ref={confirmButton} type="button" className="primary-button" disabled={locked || conflict || stale} onClick={confirm}>確認{actionLabels[mode]}</button><button type="button" className="secondary-button" disabled={locked} onClick={() => setConfirmation(null)}>返回操作內容</button></div></section>}
    </form>}
  </section>
}
