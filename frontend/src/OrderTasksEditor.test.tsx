import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import OrderTasksEditor from './OrderTasksEditor'
import type { EditorItem, EditorTask } from './orderEditorModel'
import { OrderFieldIssues } from './orderFieldContext'

const items: EditorItem[] = [
  { clientRef: 'item-a', sequence: '1', itemKind: 'CARGO', containerType: '', containerNo: '', packageQty: '', grossWeight: '', cbm: '', lastPickupDate: '', deliveryCutoffAt: '' },
  { clientRef: 'item-b', sequence: '2', itemKind: 'CONTAINER', containerType: '', containerNo: '合成櫃號', packageQty: '', grossWeight: '', cbm: '', lastPickupDate: '', deliveryCutoffAt: '' },
]
function task(ref: string, changes: Partial<EditorTask> = {}): EditorTask {
  return { clientRef: ref, itemRef: 'item-a', taskKind: '', pickupName: '', pickupAddress: '', deliveryName: '', deliveryAddress: '', siteContact: null,
    plannedStartAt: '', plannedEndAt: '', predecessorRefs: [], requiredTask: '', ...changes }
}
function Harness({ initial, changed, disabled = false }: { initial: EditorTask[]; changed: (tasks: EditorTask[]) => void; disabled?: boolean }) {
  const [tasks, setTasks] = useState(initial)
  return <OrderTasksEditor tasks={tasks} items={items} disabled={disabled} onChange={(next) => { changed(next); setTasks(next) }} onAdd={() => {}} onRemove={() => {}} />
}

describe('order task editing', () => {
  it('reorders whole tasks while preserving references, identities, and dependencies', () => {
    const changed = vi.fn()
    const initial = [task('task-a', { serverId: 'saved-a', taskNo: 'SYNTHETIC-A' }), task('task-b', { serverId: 'saved-b', taskNo: 'SYNTHETIC-B', itemRef: 'item-b', predecessorRefs: ['task-a'] })]
    render(<Harness initial={initial} changed={changed} />)
    fireEvent.click(screen.getByRole('button', { name: '上移任務 2' }))
    expect(changed).toHaveBeenLastCalledWith([initial[1], initial[0]])
    const moved = screen.getByRole('group', { name: '任務 1' })
    expect(within(moved).getByLabelText('任務參照')).toHaveValue('task-b')
    expect(within(moved).getByLabelText('任務參照')).toHaveAttribute('readonly')
    expect(moved).toHaveFocus()
    expect(within(moved).getByText('任務識別').closest('details')).not.toHaveAttribute('open')
    expect(within(moved).getByLabelText('所屬項目')).toHaveValue('item-b')
  })

  it('changes one relation without silently deleting unrelated or dangling predecessor references', () => {
    const changed = vi.fn()
    render(<Harness initial={[task('task-a'), task('task-b', { predecessorRefs: ['task-a', 'missing-ref'] })]} changed={changed} />)
    const second = screen.getByRole('group', { name: '任務 2' })
    fireEvent.click(within(second).getByRole('checkbox', { name: /任務 1/ }))
    expect(changed.mock.lastCall?.[0][1].predecessorRefs).toEqual(['missing-ref'])
    expect(within(second).getByText('失聯前置任務')).toBeVisible()
    fireEvent.click(within(second).getByRole('checkbox', { name: /失聯前置任務/ }))
    expect(changed.mock.lastCall?.[0][1].predecessorRefs).toEqual([])
  })

  it('allows cross-item dependencies and never offers the task itself as an ordinary predecessor', () => {
    const changed = vi.fn()
    render(<Harness initial={[task('task-a'), task('task-b', { itemRef: 'item-b' })]} changed={changed} />)
    const second = screen.getByRole('group', { name: '任務 2' })
    const dependencies = within(second).getByRole('group', { name: '前置任務' })
    expect(within(dependencies).queryByRole('checkbox', { name: /任務 2/ })).not.toBeInTheDocument()
    expect(within(dependencies).queryByText('task-a')).not.toBeInTheDocument()
    fireEvent.click(within(dependencies).getByRole('checkbox', { name: /任務 1/ }))
    expect(changed.mock.lastCall?.[0][1]).toMatchObject({ itemRef: 'item-b', predecessorRefs: ['task-a'] })
  })

  it('retains raw text, microseconds and offset when changing the item relation', () => {
    const changed = vi.fn()
    const original = task('task-a', {
      taskKind: '  e\u0301 合成作業\u0000  ', pickupAddress: '合成起點\r\n二樓',
      plannedStartAt: '2026-12-01T09:00:00.123456+23:59', plannedEndAt: '2026-12-02T09:00:00.654321+23:59',
    })
    render(<Harness initial={[original]} changed={changed} />)
    fireEvent.change(screen.getByLabelText('所屬項目'), { target: { value: 'item-b' } })
    expect(changed).toHaveBeenLastCalledWith([{ ...original, itemRef: 'item-b' }])
  })

  it('requires an explicit second action before discarding nonempty contact details', () => {
    const changed = vi.fn()
    const original = task('task-a', { siteContact: { name: '合成聯絡人', phone: '+44 0000 ext 1' } })
    render(<Harness initial={[original]} changed={changed} />)
    fireEvent.click(screen.getByRole('button', { name: '移除現場聯絡資料' }))
    expect(screen.getByRole('alertdialog')).toBeVisible()
    expect(changed).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '保留聯絡資料' }))
    expect(screen.getByLabelText('現場聯絡人姓名')).toHaveValue('合成聯絡人')
    fireEvent.click(screen.getByRole('button', { name: '移除現場聯絡資料' }))
    fireEvent.click(screen.getByRole('button', { name: '確認移除聯絡資料' }))
    expect(changed).toHaveBeenLastCalledWith([{ ...original, siteContact: null }])
  })

  it('keeps an explicitly present empty contact object and restricts requiredTask to unknown or true', () => {
    const changed = vi.fn()
    render(<Harness initial={[task('task-a')]} changed={changed} />)
    fireEvent.click(screen.getByRole('button', { name: '新增現場聯絡資料' }))
    expect(changed.mock.lastCall?.[0][0].siteContact).toEqual({ name: '', phone: '' })
    const required = screen.getByLabelText('必要任務標記')
    expect(within(required).getAllByRole('option').map((option) => (option as HTMLOptionElement).value)).toEqual(['', 'true'])
    fireEvent.change(required, { target: { value: 'true' } })
    expect(changed.mock.lastCall?.[0][0].requiredTask).toBe('true')
    fireEvent.change(required, { target: { value: '' } })
    expect(changed.mock.lastCall?.[0][0].requiredTask).toBe('')
  })

  it('disables mutations while the parent is saving an unresolved snapshot', () => {
    const changed = vi.fn(), add = vi.fn(), remove = vi.fn()
    render(<OrderTasksEditor tasks={[task('task-a')]} items={items} disabled onChange={changed} onAdd={add} onRemove={remove} />)
    expect(screen.getByLabelText('作業類型')).toBeDisabled()
    expect(screen.getByLabelText('所屬項目')).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '新增任務' }))
    fireEvent.click(screen.getByRole('button', { name: '移除任務 1' }))
    expect(add).not.toHaveBeenCalled()
    expect(remove).not.toHaveBeenCalled()
    expect(changed).not.toHaveBeenCalled()
  })

  it('links server field issues to relation controls and keeps their messages visible', () => {
    render(<OrderFieldIssues.Provider value={[{ path: '/tasks/0/itemRef', message: '請選擇同單項目' }, { path: '/tasks/0/predecessorRefs', message: '請解除循環依賴' }]}><OrderTasksEditor tasks={[task('task-a')]} items={items} disabled={false} onChange={() => {}} onAdd={() => {}} onRemove={() => {}} /></OrderFieldIssues.Provider>)
    expect(screen.getByLabelText('所屬項目')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('所屬項目')).toHaveAccessibleDescription(/請選擇同單項目/)
    expect(screen.getByRole('group', { name: '前置任務' })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('請解除循環依賴')).toBeVisible()
  })
})
