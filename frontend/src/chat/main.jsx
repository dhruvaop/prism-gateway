import React from 'react'
import { createRoot } from 'react-dom/client'
import ChatApp from './ChatApp.jsx'
import './chat.css'
import './chat-extra.css'
import './chat-auth.css'

createRoot(document.getElementById('root')).render(<React.StrictMode><ChatApp /></React.StrictMode>)
