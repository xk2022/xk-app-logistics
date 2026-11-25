import { createContext } from 'react'
import type { EditorIssue } from './orderEditorModel'

export const OrderFieldIssues = createContext<EditorIssue[]>([])
