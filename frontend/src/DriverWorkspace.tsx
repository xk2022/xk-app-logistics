import { useState } from 'react'
import type { MyTask } from './tmsApi'
import MyTasks from './MyTasks'
import DriverTaskSession from './DriverTaskSession'
import './driver-execution.css'
export interface DriverWorkspaceProps { onExpired: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void; logoutPending?: boolean }
export interface DriverTaskSelection { assignmentId: string; taskId: string; taskNo: string }
export default function DriverWorkspace({ onExpired, onDirtyChange, onBusyChange, logoutPending = false }: DriverWorkspaceProps) {
  const [selected, setSelected] = useState<DriverTaskSelection | null>(null)
  const open = (task: MyTask) => setSelected({ assignmentId: task.assignmentId, taskId: task.taskId, taskNo: task.taskNo })
  return <section className="driver-execution-workspace">{selected ? <DriverTaskSession key={selected.assignmentId} selected={selected} onExpired={onExpired} onDirtyChange={onDirtyChange} onBusyChange={onBusyChange} logoutPending={logoutPending} onBack={() => setSelected(null)} /> : logoutPending ? <section className="card" role="status">正在安全登出，本人任務資料已隱去。</section> : <MyTasks onExpired={onExpired} onOpenAssignment={open} />}</section>
}
