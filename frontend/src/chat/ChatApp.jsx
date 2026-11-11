import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowUp, BookOpen, Brain, Check, ChevronDown, Code2, Globe2, LoaderCircle, Lock, LogOut, MessageSquarePlus, ShieldCheck, Sparkles, Trash2, WandSparkles, X, Zap } from 'lucide-react'
import PasswordReset from '../PasswordReset.jsx'
import { redactModelDetails } from './redact.js'

const API = import.meta.env.VITE_API_URL ?? ''
const savedToken = () => sessionStorage.getItem('prism_chat_token') || ''
const resetFromUrl = () => new URLSearchParams(window.location.search).get('reset') || ''
const inr = paise => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(Number(paise || 0) / 100)
let razorpayLoader
function loadRazorpay() {
  if (window.Razorpay) return Promise.resolve()
  if (!razorpayLoader) razorpayLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://checkout.razorpay.com/v1/checkout.js'
    script.async = true
    script.onload = resolve
    script.onerror = () => { razorpayLoader = null; reject(new Error('Razorpay Checkout could not load. Check your connection and try again.')) }
    document.body.appendChild(script)
  })
  return razorpayLoader
}

const MODEL_LABELS = { auto: 'Auto · routes by task', fast: 'Fast · quick answers', smart: 'Smart · deeper work' }
const CAPABILITIES = [
  { icon: Globe2, label: 'Web search' },
  { icon: BookOpen, label: 'Deep research' },
  { icon: Brain, label: 'Reasoning' },
  { icon: Code2, label: 'Code' },
  { icon: Zap, label: 'Fast answers' },
]
const STARTERS = [
  { id: 'search', icon: Globe2, title: 'Search the web', hint: 'Live, current answers', text: 'Search the web for the latest AI gateway news' },
  { id: 'reasoning', icon: Brain, title: 'Reason step by step', hint: 'Deeper analysis', text: 'Prove that the square root of 2 is irrational step by step' },
  { id: 'code', icon: Code2, title: 'Write or debug code', hint: 'Code and tests', text: 'Write a Python function that removes duplicates from a list while keeping order' },
  { id: 'research', icon: BookOpen, title: 'Research a topic', hint: 'Sources and comparison', text: 'Research the trade-offs between event sourcing and CRUD for an audit-heavy system' },
]
const TASK_STATUS = {
  web_search: { icon: Globe2, active: 'Searching the web', done: 'Searched the web' },
  research: { icon: BookOpen, active: 'Researching your question', done: 'Researched your question' },
  reasoning: { icon: Brain, active: 'Reasoning through your request', done: 'Reasoned through your request' },
  code: { icon: Code2, active: 'Working through the code', done: 'Worked through the code' },
}

export default function ChatApp() {
  const [token, setToken] = useState(() => resetFromUrl() ? '' : savedToken())
  const [user, setUser] = useState(null)
  const [conversations, setConversations] = useState([])
  const [conversation, setConversation] = useState(null)
  const [model, setModel] = useState('auto')
  const [verify, setVerify] = useState(false)
  const [prompt, setPrompt] = useState('')
  const [busy, setBusy] = useState(false)
  const [thinking, setThinking] = useState(false)
  const [servedBy, setServedBy] = useState('')
  const [taskStatus, setTaskStatus] = useState(null)
  const [error, setError] = useState('')
  const [login, setLogin] = useState({ username: '', password: '' })
  const [signup, setSignup] = useState({ name: '', email: '', password: '' })
  const [authMode, setAuthMode] = useState('signin')
  const [billing, setBilling] = useState(null)
  const [paywall, setPaywall] = useState(false)
  const [payBusy, setPayBusy] = useState('')
  const [billingNotice, setBillingNotice] = useState('')
  const [recovery, setRecovery] = useState(() => new URLSearchParams(window.location.search).has('reset'))
  const [notice, setNotice] = useState('')
  const messagesRef = useRef(null)
  const composerRef = useRef(null)

  useEffect(() => {
    const pane = messagesRef.current
    if (pane) pane.scrollTop = pane.scrollHeight
  }, [conversation, busy, thinking])

  const allowed = useMemo(() => {
    try { return Array.isArray(user?.model_allowlist) ? user.model_allowlist : JSON.parse(user?.model_allowlist || '[]') } catch { return [] }
  }, [user])

  function clearSession() {
    sessionStorage.removeItem('prism_chat_token')
    setToken('')
    setUser(null)
    setConversations([])
    setConversation(null)
    setBilling(null)
    setPaywall(false)
    setPayBusy('')
    setBillingNotice('')
    setServedBy('')
    setTaskStatus(null)
    setPrompt('')
    setError('')
    setThinking(false)
    setBusy(false)
    setLogin({ username: '', password: '' })
  }

  async function api(path, options = {}) {
    const response = await fetch(`${API}${path}`, { ...options, headers: { ...(options.headers || {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) } })
    const data = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(data.error?.message || `Request failed (${response.status})`)
    return data
  }

  async function refresh() {
    const [me, list, usage, status] = await Promise.all([api('/app/me'), api('/app/conversations'), api('/app/usage'), api('/app/billing/status').catch(() => null)])
    setUser({ ...me.user, usage: usage.usage })
    setBilling(status)
    let models=me.user?.model_allowlist||[];if(typeof models==='string'){try{models=JSON.parse(models)}catch{models=[]}}setModel(current=>models.includes(current)?current:models.includes('auto')?'auto':models[0]||'auto')
    setConversations(list.conversations || [])
  }

  useEffect(() => {
    if (token) refresh().catch(e => { clearSession(); setError(e.message) })
  }, [token])

  async function signIn(e) {
    e.preventDefault(); setError('')
    try { const result = await fetch(`${API}/app/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(login) }); const data = await result.json(); if (!result.ok) throw new Error(data.error?.message || 'Sign in failed'); clearSession(); sessionStorage.setItem('prism_chat_token', data.token); setToken(data.token); setUser(data.user) } catch (e) { setError(e.message) }
  }

  async function signUp(e) {
    e.preventDefault(); setError(''); setBillingNotice('')
    try {
      const result = await fetch(`${API}/app/signup`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(signup) })
      const data = await result.json().catch(() => ({}))
      if (!result.ok) throw new Error(data.error?.message || 'Sign up failed')
      clearSession()
      sessionStorage.setItem('prism_chat_token', data.token); setToken(data.token); setUser(data.user)
    } catch (e) { setError(e.message) }
  }

  async function subscribe(planId) {
    setPayBusy(planId); setError(''); setBillingNotice('')
    try {
      const order = await api('/app/billing/order', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ plan: planId }) })
      await loadRazorpay()
      const checkout = new window.Razorpay({
        key: order.key_id, amount: order.amount, currency: order.currency, name: order.name,
        description: order.description, order_id: order.order_id, prefill: order.prefill, notes: order.notes,
        theme: { color: '#9587f7' },
        handler: async payment => {
          try {
            await api('/app/billing/verify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payment) })
            await refresh(); setPaywall(false); setBillingNotice('Subscription active. Your demo limit is lifted.'); setError('')
          } catch (e) { setError(e.message) } finally { setPayBusy('') }
        },
        modal: { ondismiss: () => setPayBusy('') }
      })
      checkout.on('payment.failed', response => { setError(response?.error?.description || 'Payment failed. Please try again.'); setPayBusy('') })
      checkout.open()
    } catch (e) { setError(e.message); setPayBusy('') }
  }

  async function openConversation(id) { setServedBy(''); try { const result = await api(`/app/conversations/${id}`); setConversation(result.conversation) } catch (e) { setError(e.message) } }
  async function newConversation() { setServedBy(''); try { const result = await api('/app/conversations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title: 'New conversation' }) }); setConversation(result.conversation); await refresh() } catch (e) { setError(e.message) } }
  async function removeConversation(id) { try { await api(`/app/conversations/${id}`, { method: 'DELETE' }); if (conversation?.id === id) setConversation(null); await refresh() } catch (e) { setError(e.message) } }


  async function send(e) {
    e.preventDefault(); if (!prompt.trim() || busy) return
    if (trialBlocked) { setPaywall(true); return }
    setBusy(true); setThinking(false); setServedBy(''); setTaskStatus(null); setError('')
    let current = conversation
    try {
      if (!current) { const created = await api('/app/conversations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title: prompt.trim().slice(0, 48) }) }); current = created.conversation; setConversation(current) }
      const text = prompt.trim(); setPrompt(''); setConversation(c => ({ ...(c || current), messages: [...(c?.messages || []), { role: 'user', content: text }, { role: 'assistant', content: '' }] }))
      const response = await fetch(`${API}/app/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify({ model, verify, conversation_id: current.id, messages: [...(current.messages || []).map(({ role, content }) => ({ role, content })), { role: 'user', content: text }] }) })
      if (!response.ok) { const body = await response.json().catch(() => ({})); if (response.status === 402) setPaywall(true); throw new Error(body.error?.message || `Chat failed (${response.status})`) }
      const hidden = response.headers.get('x-prism-model-hidden') === 'true'
      if (!hidden) {
        const serving = response.headers.get('x-prism-provider') || '', creator = response.headers.get('x-prism-model-creator') || '', family = response.headers.get('x-prism-model-family') || '', tier = response.headers.get('x-prism-tier') || ''
        setServedBy(serving === 'cache' ? 'Cached reply' : /^(alpha|beta)\//.test(serving) ? `Mock provider fallback · ${serving}` : serving ? `Served by ${serving}${creator ? ` · ${creator}` : ''}${family ? ` ${family}` : ''}${tier ? ` · ${tier} tier` : ''}` : '')
      } else setServedBy('Served by · model details hidden')
      const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = ''
      const applyLine = line => {
        if (!line.startsWith('data: ')) return
        const data = line.slice(6).trim()
        if (!data || data === '[DONE]') return
        try {
          const parsed = JSON.parse(data)
          if (parsed.error) { setTaskStatus(null); return }
          const delta = parsed.choices?.[0]?.delta
          if (!delta) return
          const status = delta.prism_status
          if (status?.type && TASK_STATUS[status.type]) {
            setTaskStatus({ type: status.type, query: status.query || '', active: true })
            setThinking(false)
            return
          }
          if (typeof delta.content === 'string' && delta.content) {
            setTaskStatus(current => current ? { ...current, active: false } : current)
            setThinking(false)
            setConversation(c => c ? { ...c, messages: c.messages.map((m, i) => i === c.messages.length - 1 ? { ...m, content: m.content + delta.content } : m) } : c)
          } else if (delta.reasoning || delta.reasoning_content) {
            setThinking(true)
          }
        } catch {}
      }
      while (true) { const { value, done } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); const lines = buffer.split('\n'); buffer = lines.pop() || ''; lines.forEach(applyLine) }
      if (buffer) applyLine(buffer)
      setThinking(false)
      const refreshed = await api(`/app/conversations/${current.id}`)
      const saved = refreshed.conversation
      const answered = [...(saved?.messages || [])].reverse().find(m => m.role === 'assistant' && m.content?.trim())
      setConversation(saved)
      if (!answered) { setTaskStatus(null); setError('The model returned no text. Please try again or choose another model.') }
      await refresh()
    } catch (e) {
      setTaskStatus(null)
      setError(redactModelDetails(e.message))
      // Rejected requests are not persisted. Re-sync so an optimistic blocked
      // turn cannot poison the replayed history on the next message.
      if (current?.id) {
        try { const refreshed = await api(`/app/conversations/${current.id}`); setConversation(refreshed.conversation) } catch {}
      }
    } finally { setBusy(false); setThinking(false); setTaskStatus(current => current ? { ...current, active: false } : current) }
  }

  const usage = user?.usage || {}, cap = Number(usage.token_cap || 0), used = Number(usage.total_tokens || 0), ratio = cap ? Math.min(100, used / cap * 100) : 0
  const warning = cap && ratio >= 95 ? 'You have used 95% or more of this month’s token allowance.' : cap && ratio >= 90 ? 'You have used 90% of this month’s token allowance.' : cap && ratio >= 80 ? 'You have used 80% of this month’s token allowance.' : cap && ratio >= 70 ? 'You have used 70% of this month’s token allowance.' : ''
  const estimatedTokens = prompt.trim() ? Math.max(1, Math.ceil(prompt.trim().length / 4)) : 0
  const trialRemaining = Number(billing?.trial?.remaining ?? 0)
  const selfServe = Boolean(billing && billing.unlimited !== true)
  const trialBlocked = selfServe && !billing?.subscription && trialRemaining <= 0
  const plans = billing?.plans?.length ? billing.plans : [{ id: 'pro', name: 'Pro', amount_paise: 49900 }, { id: 'enterprise', name: 'Enterprise', amount_paise: 199900 }]

  if (!token && recovery) return <main className="chat-login"><div className="chat-login-card"><div className="chat-logo"><WandSparkles size={18}/> PRISM <span>CHAT</span></div><PasswordReset api={API} basePath="/app" onCancel={message => { setRecovery(false); setNotice(message); setError('') }}/></div></main>
  if (!token) return <main className="chat-login"><div className="chat-login-card"><div className="chat-logo"><WandSparkles size={18}/> PRISM <span>CHAT</span></div>
    <div className="chat-auth-tabs"><button type="button" className={authMode === 'signin' ? 'selected' : ''} onClick={() => { setAuthMode('signin'); setError(''); setNotice('') }}>Sign in</button><button type="button" className={authMode === 'signup' ? 'selected' : ''} onClick={() => { setAuthMode('signup'); setError(''); setNotice('') }}>Create account</button></div>
    <h1>{authMode === 'signup' ? 'Start free' : 'Welcome back'}</h1>
    <p>{authMode === 'signup' ? 'Create an account and try Prism with 5 free requests.' : 'Search, reason, write code, and get fast answers in one private chat.'}</p>
    <div className="chat-capabilities" aria-label="Prism Chat capabilities">{CAPABILITIES.map(capability => <span key={capability.label}><capability.icon size={13}/>{capability.label}</span>)}</div>
    <div className="chat-routing-note"><WandSparkles size={12}/> Auto routes each request — web search, reasoning, or code — to the right model.</div>
    <div className="chat-steps" aria-label="How Prism Chat works"><span><i>1</i>Sign in or start free</span><span><i>2</i>Ask anything · provider details stay private</span><span><i>3</i>5 free requests, then Pro or Enterprise</span></div>
    {authMode === 'signin' ? <form onSubmit={signIn}>
      <label>EMAIL OR USERNAME<input autoComplete="username" placeholder="you@company.com or username" value={login.username} onChange={e => setLogin({ ...login, username: e.target.value })} required /></label>
      <label>PASSWORD<input type="password" autoComplete="current-password" value={login.password} onChange={e => setLogin({ ...login, password: e.target.value })} required /></label>
      <button type="button" className="password-reset-back" onClick={() => { setRecovery(true); setError(''); setNotice('') }}>Forgot password?</button>
      {notice && <div className="password-reset-message" role="status">{notice}</div>}
      {error && <div className="chat-error" role="alert">{error}</div>}
      <button className="chat-primary">Sign in <ArrowUp size={15}/></button>
    </form> : <form onSubmit={signUp}>
      <label>FULL NAME<input autoComplete="name" placeholder="Your name" value={signup.name} onChange={e => setSignup({ ...signup, name: e.target.value })} required minLength={2} maxLength={100} /></label>
      <label>EMAIL<input type="email" autoComplete="email" placeholder="you@company.com" value={signup.email} onChange={e => setSignup({ ...signup, email: e.target.value })} required /></label>
      <label>PASSWORD<input type="password" autoComplete="new-password" placeholder="At least 8 characters" value={signup.password} onChange={e => setSignup({ ...signup, password: e.target.value })} required minLength={8} maxLength={72} /></label>
      {error && <div className="chat-error" role="alert">{error}</div>}
      <button className="chat-primary" disabled={!signup.name || !signup.email || signup.password.length < 8}>Create account <ArrowUp size={15}/></button>
      <small className="chat-signup-note">5 free requests included. Then Pro ₹499/month or Enterprise ₹1,999/month. Payments are handled securely by Razorpay.</small>
    </form>}
  </div></main>

  return <main className="chat-shell"><aside className="chat-sidebar"><div className="chat-logo"><WandSparkles size={18}/> PRISM <span>CHAT</span></div><button className="chat-new" onClick={newConversation}><MessageSquarePlus size={15}/> New chat</button><div className="chat-history-label">RECENT</div><div className="chat-history">{conversations.map(c => <div className={`chat-history-row ${conversation?.id === c.id ? 'selected' : ''}`} key={c.id}><button onClick={() => openConversation(c.id)}>{c.title}</button><button aria-label="Delete conversation" onClick={() => removeConversation(c.id)}><Trash2 size={13}/></button></div>)}</div><div className="chat-profile"><div className="chat-avatar">{user?.name?.[0] || 'U'}</div><div><b>{user?.name}</b><small>{user?.team}</small></div><button title="Sign out" onClick={clearSession}><LogOut size={15}/></button></div></aside>
    <section className="chat-main"><header className="chat-top"><div><b>{conversation?.title || 'New conversation'}</b><small>{user?.team} workspace{billing?.unlimited ? ' · unlimited' : ''}</small></div><div className="chat-model" title={model === 'auto' ? 'Prism routes web search, reasoning, and code requests automatically.' : 'You picked this mode manually.'}><WandSparkles size={14}/><select value={model} onChange={e => setModel(e.target.value)}>{allowed.map(x => <option key={x} value={x}>{MODEL_LABELS[x] || x}</option>)}</select><ChevronDown size={13}/></div></header>
      {billing?.subscription && <div className="chat-trial chat-trial-active"><ShieldCheck size={13}/> {String(billing.subscription.plan || 'Plan').replace(/^./, c => c.toUpperCase())} subscription active</div>}
      {selfServe && !billing?.subscription && !trialBlocked && <div className="chat-trial"><div><b>{trialRemaining} of {billing?.trial?.limit ?? 5} free requests left</b><span>Then choose a plan to keep chatting.</span></div><button type="button" onClick={() => setPaywall(true)}>View plans</button></div>}
      {trialBlocked && <div className="chat-trial chat-trial-blocked"><div><b>Free demo complete</b><span>Subscribe to continue this conversation.</span></div><button type="button" onClick={() => setPaywall(true)}>Choose a plan</button></div>}
      {billingNotice && <div className="chat-notice" role="status">{billingNotice}</div>}
      {cap > 0 && <div className="token-meter"><span>Monthly tokens</span><div><i style={{ width: `${ratio}%` }}/></div><b>{used.toLocaleString()} / {cap.toLocaleString()}</b></div>}
      {warning && <div className="token-warning">{warning}</div>}
      <div className="chat-messages" ref={messagesRef}>{!conversation?.messages?.length && <div className="chat-welcome"><div className="chat-welcome-icon"><WandSparkles size={24}/></div><h1>What can I help with?</h1><p>One private chat for web search, reasoning, code, and fast answers. Leave <b>Auto</b> selected and Prism routes each request to the right model.</p><div className="chat-capabilities chat-capabilities-wide" aria-label="Prism Chat capabilities">{CAPABILITIES.map(capability => <span key={capability.label}><capability.icon size={13}/>{capability.label}</span>)}</div><div className="chat-starters">{STARTERS.map(starter => <button key={starter.id} type="button" className={`chat-starter ${starter.id}`} onClick={() => { setPrompt(starter.text); requestAnimationFrame(() => composerRef.current?.focus()) }}><span className="chat-starter-icon"><starter.icon size={15}/></span><span><b>{starter.title}</b><small>{starter.hint}</small></span></button>)}</div><small className="chat-privacy-note"><ShieldCheck size={12}/> Model and provider details stay hidden in chat.</small></div>}{(conversation?.messages || []).map((m,i) => {
        const last = i === conversation.messages.length - 1, pending = busy && last
        if (m.role === 'assistant' && !m.content && !pending) return null
        const content = m.role === 'assistant' ? redactModelDetails(m.content) : m.content
        const statusConfig = last && m.role === 'assistant' && taskStatus ? TASK_STATUS[taskStatus.type] : null
        const StatusIcon = statusConfig?.icon
        return <article className={`chat-message ${m.role}`} key={`${i}-${m.role}`}><div className="message-avatar">{m.role === 'assistant' ? <WandSparkles size={15}/> : user?.name?.[0]}</div><div className="message-body">{statusConfig && <div className={`chat-task-status ${taskStatus.active ? 'active' : 'done'}`} role="status"><StatusIcon size={13}/><span><b>{taskStatus.active ? statusConfig.active : statusConfig.done}</b>{taskStatus.type === 'web_search' && taskStatus.query && <em>“{taskStatus.query}”</em>}</span>{taskStatus.active && <span className="chat-task-dots" aria-hidden="true"><span/><span/><span/></span>}</div>}<div className="message-text">{content || (pending ? (taskStatus?.active ? null : thinking ? <span className="chat-thinking">Thinking…</span> : <LoaderCircle className="chat-spin" size={16}/>) : '')}</div>{last && m.role === 'assistant' && servedBy && <div className="chat-provider">Served by · model details hidden</div>}</div></article>
      })}</div>
      <form className="chat-composer" onSubmit={send}><textarea ref={composerRef} rows="3" value={prompt} onChange={e => setPrompt(e.target.value)} placeholder="Ask anything — web search, reasoning, code, or a quick question…" onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(e) } }}/><div className="chat-composer-bottom"><span className="token-estimate">{estimatedTokens ? `~${estimatedTokens} prompt tokens` : 'Enter to send · Shift+Enter for a new line'}</span><label className={!verify ? 'verify-disabled' : ''}><input type="checkbox" checked={verify} onChange={e => setVerify(e.target.checked)} disabled={!JSON.parse(user?.verify_models || '[]').length}/> <ShieldCheck size={13}/> Verify answer</label><button disabled={busy || !prompt.trim()}>{busy ? <LoaderCircle className="chat-spin" size={16}/> : <ArrowUp size={17}/>}</button></div></form>
      {error && <div className="chat-error chat-error-float">{error}<button onClick={() => setError('')}>×</button></div>}
      <footer className="chat-disclaimer"><Check size={12}/> Responses can be inaccurate. Check important details.</footer>
    </section>
    {paywall && <Paywall billing={billing} plans={plans} busy={payBusy} notice={billingNotice} error={error} onClose={() => setPaywall(false)} onSubscribe={subscribe}/>}
  </main>
}

function Paywall({ billing, plans, busy, notice, error, onClose, onSubscribe }) {
  const features = plan => plan.id === 'enterprise'
    ? ['Everything in Pro', 'Higher request limits', 'Priority routing for teams']
    : ['Unlimited chat requests', 'Fast and smart routing', 'Conversation history']
  return <div className="chat-paywall-backdrop" role="dialog" aria-modal="true" aria-labelledby="paywall-title" onClick={onClose}>
    <section className="chat-paywall" onClick={e => e.stopPropagation()}>
      <button type="button" className="chat-paywall-close" aria-label="Close plans" onClick={onClose}><X size={16}/></button>
      <div className="chat-paywall-icon"><Lock size={20}/></div>
      <h2 id="paywall-title">Keep the conversation going</h2>
      <p>Your free demo is complete. Choose a plan to continue with unlimited chat. Cancel anytime.</p>
      {notice && <div className="chat-notice" role="status">{notice}</div>}
      {error && <div className="chat-error" role="alert">{error}</div>}
      <div className="chat-plans">{plans.map(plan => <article className={`chat-plan ${plan.id}`} key={plan.id}>
        <div className="chat-plan-head"><span>{plan.name}</span>{plan.id === 'pro' && <em>MOST POPULAR</em>}</div>
        <b className="chat-plan-price">{inr(plan.amount_paise)}<small>/month</small></b>
        <ul>{features(plan).map(feature => <li key={feature}><Check size={12}/> {feature}</li>)}</ul>
        <button type="button" onClick={() => onSubscribe(plan.id)} disabled={Boolean(busy)}>{busy === plan.id ? <LoaderCircle className="chat-spin" size={15}/> : <>Choose {plan.name} <Sparkles size={13}/></>}</button>
      </article>)}</div>
      <small className="chat-paywall-foot"><ShieldCheck size={12}/> Secure payment by Razorpay. {billing?.currency || 'INR'} pricing, billed monthly.</small>
    </section>
  </div>
}
