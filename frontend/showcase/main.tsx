import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import ShowcaseApp from './ShowcaseApp'
createRoot(document.getElementById('root')!).render(<StrictMode><ShowcaseApp/></StrictMode>)
