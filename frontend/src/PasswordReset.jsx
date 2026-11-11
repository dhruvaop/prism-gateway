import { useState } from 'react'
import { ArrowLeft, KeyRound, LoaderCircle, Mail } from 'lucide-react'
import './password-reset.css'

export default function PasswordReset({ api, basePath, onCancel }) {
  const initialToken = new URLSearchParams(window.location.search).get('reset') || ''
  const [token, setToken] = useState(initialToken)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event) {
    event.preventDefault()
    setBusy(true); setError(''); setMessage('')
    try {
      if (token && password !== confirmation) throw new Error('The passwords do not match.')
      const path = token ? '/password/reset' : '/password/forgot'
      const response = await fetch(`${api}${basePath}${path}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(token ? { token, password } : { email }),
      })
      const data = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(data.error?.message || 'Unable to process the password reset.')
      if (token) {
        window.history.replaceState({}, '', window.location.pathname)
        onCancel(data.message)
        return
      }
      if (data.reset_link) {
        const developmentToken = new URL(data.reset_link).searchParams.get('reset')
        setToken(developmentToken || '')
        setMessage('Email is not configured. Continue below to use the local development reset link.')
      } else {
        setMessage(data.message)
      }
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }

  return <form className="password-reset" onSubmit={submit}>
    <div className="password-reset-icon"><KeyRound size={20}/></div>
    <h2>{token ? 'Choose a new password' : 'Reset your password'}</h2>
    <p>{token ? 'Use 8–72 characters. This reset link can be used only once.' : 'Enter your account email. If it matches an account, we will send a single-use reset link.'}</p>
    {!token && <label>EMAIL ADDRESS<div><Mail size={15}/><input type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} required autoFocus/></div></label>}
    {token && <>
      <label>NEW PASSWORD<div><KeyRound size={15}/><input type="password" autoComplete="new-password" minLength="8" maxLength="72" value={password} onChange={e => setPassword(e.target.value)} required autoFocus/></div></label>
      <label>CONFIRM PASSWORD<div><KeyRound size={15}/><input type="password" autoComplete="new-password" minLength="8" maxLength="72" value={confirmation} onChange={e => setConfirmation(e.target.value)} required/></div></label>
    </>}
    {message && <div className="password-reset-message" role="status">{message}</div>}
    {error && <div className="password-reset-error" role="alert">{error}</div>}
    <button className="password-reset-submit" disabled={busy}>{busy ? <LoaderCircle className="spin" size={15}/> : token ? 'Update password' : 'Send reset link'}</button>
    <button type="button" className="password-reset-back" onClick={() => { window.history.replaceState({}, '', window.location.pathname); onCancel('') }}><ArrowLeft size={14}/> Back to sign in</button>
  </form>
}
