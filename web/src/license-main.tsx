import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import './index.css'
import { License } from '@/pages/license'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <License />
  </StrictMode>,
)
