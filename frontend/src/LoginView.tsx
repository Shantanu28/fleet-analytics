import { useState, type FormEvent } from 'react'
import { ApiError } from './api/client'
import { useSession } from './auth/session'

export function LoginView() {
  const { signIn } = useSession()
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
    <main>
      <h1>Fleet Analytics</h1>
      <form onSubmit={onSubmit}>
        <div>
          <label htmlFor="username">Username</label>
          <input id="username" name="username" value={username} autoComplete="username"
                 onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div>
          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" value={password} autoComplete="current-password"
                 onChange={(e) => setPassword(e.target.value)} />
        </div>
        {error && <p role="alert">{error}</p>}
        <button type="submit" disabled={busy}>Sign in</button>
      </form>
    </main>
  )
}
