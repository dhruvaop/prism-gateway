import { useState } from 'react'
import { ArrowRight, Eye, EyeOff, Layers3, LoaderCircle, LockKeyhole, Mail, ShieldCheck, UserRound } from 'lucide-react'

export default function AuthScreen({ api, onAuthenticated }) {
  const [mode, setMode] = useState('signin')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event) {
    event.preventDefault()
    setBusy(true); setError('')
    try {
      const response = await fetch(`${api}/auth/${mode}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, email, password }),
      })
      const data = await response.json()
      if (!response.ok) throw new Error(data.error?.message || 'Unable to authenticate.')
      onAuthenticated(data)
    } catch (e) {
      setError(e.message || 'Gateway is unavailable. Please try again.')
    } finally { setBusy(false) }
  }

  const signingUp = mode === 'signup'
  return <main className="auth-screen"><div className="auth-glow auth-glow-one"/><div className="auth-glow auth-glow-two"/>
    <section className="auth-card">
      <div className="auth-brand"><div className="brand-mark"><Layers3 size={21}/></div><div><b>prism</b><span>GATEWAY CONTROL PLANE</span></div></div>
      <div className="auth-eyebrow"><ShieldCheck size={13}/> SECURE WORKSPACE ACCESS</div>
      <h1>{signingUp ? 'Create your account' : 'Welcome back'}<span>.</span></h1>
      <p className="auth-subtitle">{signingUp ? 'Set up your Prism workspace access.' : 'Sign in to manage your AI gateway.'}</p>
      <div className="auth-tabs"><button className={!signingUp ? 'selected' : ''} onClick={() => { setMode('signin'); setError('') }}>Sign in</button><button className={signingUp ? 'selected' : ''} onClick={() => { setMode('signup'); setError('') }}>Sign up</button></div>
      <form onSubmit={submit} className="auth-form">
        {signingUp && <label className="auth-field"><span>FULL NAME</span><div><UserRound size={16}/><input autoComplete="name" value={name} onChange={e => setName(e.target.value)} placeholder="Your name" required minLength={2} maxLength={100}/></div></label>}
        <label className="auth-field"><span>EMAIL ADDRESS</span><div><Mail size={16}/><input type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="you@company.com" required/></div></label>
        <label className="auth-field"><span>PASSWORD</span><div><LockKeyhole size={16}/><input type={showPassword ? 'text' : 'password'} autoComplete={signingUp ? 'new-password' : 'current-password'} value={password} onChange={e => setPassword(e.target.value)} placeholder={signingUp ? 'At least 10 characters' : 'Enter your password'} required minLength={signingUp ? 10 : 1} maxLength={72}/><button type="button" className="auth-eye" onClick={() => setShowPassword(!showPassword)} aria-label={showPassword ? 'Hide password' : 'Show password'}>{showPassword ? <EyeOff size={15}/> : <Eye size={15}/>}</button></div></label>
        {error && <div className="auth-error" role="alert">{error}</div>}
        <button className="auth-submit" disabled={busy}>{busy ? <><LoaderCircle className="spin" size={16}/> Please wait</> : <>{signingUp ? 'Create account' : 'Sign in to Prism'} <ArrowRight size={16}/></>}</button>
      </form>
      <div className="auth-security"><LockKeyhole size={13}/> Passwords are encrypted. Session tokens expire after 24 hours.</div>
    </section><div className="auth-copyright">PRISM GATEWAY <i/> PRIVATE WORKSPACE</div>
  </main>
}
