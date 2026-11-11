import { useEffect, useState } from 'react'
import { ArrowRight, Eye, EyeOff, Layers3, LoaderCircle, LockKeyhole, Mail, ShieldCheck, UserRound } from 'lucide-react'
import PasswordReset from './PasswordReset.jsx'

export default function AuthScreen({ api, onAuthenticated }) {
  const [mode, setMode] = useState(() => new URLSearchParams(window.location.search).has('reset') ? 'recovery' : 'signin')
  const [notice, setNotice] = useState('')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [signupOpen, setSignupOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetch(`${api}/auth/bootstrap`).then(r => r.ok ? r.json() : null).then(data => {
      if (cancelled) return
      setSignupOpen(Boolean(data?.signup_open))
      if (!data?.signup_open) setMode(current => current === 'signup' ? 'signin' : current)
    }).catch(() => {})
    return () => { cancelled = true }
  }, [api])

  async function submit(event) {
    event.preventDefault()
    setBusy(true); setError('')
    try {
      const response = await fetch(`${api}/auth/${mode}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, email, username, password }),
      })
      const data = await response.json()
      if (!response.ok) throw new Error(data.error?.message || 'Unable to authenticate.')
      onAuthenticated(data)
    } catch (e) {
      setError(e.message || 'Gateway is unavailable. Please try again.')
    } finally { setBusy(false) }
  }

  const signingUp = mode === 'signup'
  if (mode === 'recovery') return <main className="auth-screen"><div className="auth-glow auth-glow-one"/><div className="auth-glow auth-glow-two"/><section className="auth-card"><div className="auth-brand"><div className="brand-mark"><Layers3 size={21}/></div><div><b>prism</b><span>GATEWAY CONTROL PLANE</span></div></div><PasswordReset api={api} basePath="/auth" onCancel={message => { setMode('signin'); setNotice(message); setPassword('') }}/></section></main>
  return <main className="auth-screen"><div className="auth-glow auth-glow-one"/><div className="auth-glow auth-glow-two"/>
    <section className="auth-card">
      <div className="auth-brand"><div className="brand-mark"><Layers3 size={21}/></div><div><b>prism</b><span>GATEWAY CONTROL PLANE</span></div></div>
      <div className="auth-eyebrow"><ShieldCheck size={13}/> SECURE WORKSPACE ACCESS</div>
      <h1>{signingUp ? 'Create your account' : 'Welcome back'}<span>.</span></h1>
      <p className="auth-subtitle">{signingUp ? 'Set up your Prism workspace access.' : 'Sign in to manage your AI gateway.'}</p>
      <div className="auth-tabs"><button className={!signingUp ? 'selected' : ''} onClick={() => { setMode('signin'); setError('') }}>Sign in</button>{signupOpen && <button className={signingUp ? 'selected' : ''} onClick={() => { setMode('signup'); setError('') }}>Sign up</button>}</div>
      <form onSubmit={submit} className="auth-form">
        {signingUp && <label className="auth-field"><span>FULL NAME</span><div><UserRound size={16}/><input autoComplete="name" value={name} onChange={e => setName(e.target.value)} placeholder="Your name" required minLength={2} maxLength={100}/></div></label>}
        {signingUp && <label className="auth-field"><span>USERNAME (OPTIONAL)</span><div><UserRound size={16}/><input autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} placeholder="3–32 letters, numbers, . _ -" maxLength={32}/></div></label>}
        <label className="auth-field"><span>{signingUp ? 'EMAIL ADDRESS' : 'EMAIL OR USERNAME'}</span><div><Mail size={16}/><input type={signingUp ? 'email' : 'text'} autoComplete={signingUp ? 'email' : 'username'} value={email} onChange={e => setEmail(e.target.value)} placeholder={signingUp ? 'you@company.com' : 'Email or username'} required/></div></label>
        <label className="auth-field"><span>PASSWORD</span><div><LockKeyhole size={16}/><input type={showPassword ? 'text' : 'password'} autoComplete={signingUp ? 'new-password' : 'current-password'} value={password} onChange={e => setPassword(e.target.value)} placeholder={signingUp ? 'At least 8 characters' : 'Enter your password'} required minLength={signingUp ? 8 : 1} maxLength={72}/><button type="button" className="auth-eye" onClick={() => setShowPassword(!showPassword)} aria-label={showPassword ? 'Hide password' : 'Show password'}>{showPassword ? <EyeOff size={15}/> : <Eye size={15}/>}</button></div></label>
        {!signingUp && <button type="button" className="password-reset-back" onClick={() => { setMode('recovery'); setError(''); setNotice('') }}>Forgot password?</button>}
        {notice && <div className="password-reset-message" role="status">{notice}</div>}
        {error && <div className="auth-error" role="alert">{error}</div>}
        <button className="auth-submit" disabled={busy}>{busy ? <><LoaderCircle className="spin" size={16}/> Please wait</> : <>{signingUp ? 'Create account' : 'Sign in to Prism'} <ArrowRight size={16}/></>}</button>
      </form>
      {!signupOpen && <div className="auth-chat-signup"><span>New to Prism?</span><a href="/chat.html">Create a free chat account <ArrowRight size={12}/></a><small>Public sign-ups start in Prism Chat. This control plane is invite-only.</small></div>}
      <div className="auth-security"><LockKeyhole size={13}/> Passwords are encrypted. Session tokens expire after 24 hours.</div>
    </section><div className="auth-copyright">PRISM GATEWAY <i/> PRIVATE WORKSPACE</div>
  </main>
}
