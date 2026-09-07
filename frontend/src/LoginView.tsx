import { useState, type FormEvent } from 'react'
import { ApiError } from './api/client'
import { useSession } from './auth/session'
import './login.css'

export function LoginView() {
  const { signIn, signOutNotice } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await signIn(username, password)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="login">
      <div className="login__card">
        <h1 className="login__title">Fleet Analytics</h1>
        <p className="login__subtitle">Sign in to view your organisation’s analytics.</p>
        {signOutNotice && <p role="alert">{signOutNotice}</p>}

        <form className="login__form" onSubmit={onSubmit}>
          <div className="login__field">
            <label className="login__label" htmlFor="username">
              Username
            </label>
            <input
              className="input login__input"
              id="username"
              name="username"
              value={username}
              autoComplete="username"
              onChange={(e) => setUsername(e.target.value)}
            />
          </div>

          <div className="login__field">
            <label className="login__label" htmlFor="password">
              Password
            </label>
            <input
              className="input login__input"
              id="password"
              name="password"
              type="password"
              value={password}
              autoComplete="current-password"
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          {error && (
            <p className="login__error" role="alert">
              {error}
            </p>
          )}

          {/* Disabled while a request is in flight, so the form cannot be submitted twice. */}
          <button type="submit" className="button button--primary login__submit" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="login__note">
          This demo runs on synthetic data. Tokens are held in memory only, so a page reload requires
          signing in again.
        </p>
      </div>
    </main>
  )
}
