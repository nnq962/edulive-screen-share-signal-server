import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

const root = createRoot(document.getElementById('root')!)

// Disable StrictMode in development to avoid double mount side-effects
if (import.meta.env.DEV) {
  root.render(<App />)
} else {
  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
