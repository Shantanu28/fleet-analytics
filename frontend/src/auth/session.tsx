import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { login as loginRequest, type Role } from '../api/client'

/**
 * The token lives here, in memory only — never localStorage, a URL or a query key.
 *
 * `generation` is a monotonic counter that is burned on sign-out and never reused, so a response
 * belonging to an earlier session can always be told apart from the current one, even when the same
 * user signs back in with the same role.
 */
export type Session = {
  generation: number
  token: string
  userId: string
  organisationId: string
  displayName: string
  role: Role
}

type SessionContextValue = {
  session: Session | null
  signIn: (username: string, password: string) => Promise<void>
  signOut: () => void
  /** Ends the session only if `generation` is still the current one. */
  signOutIfCurrent: (generation: number) => void
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const queryClient = useQueryClient()

  // Mirrors `session` synchronously, so callbacks never read a stale render's value.
  const sessionRef = useRef<Session | null>(null)
  const generationRef = useRef(0)
  const attemptRef = useRef<{ generation: number; controller: AbortController } | null>(null)

  const applySession = useCallback((next: Session | null) => {
    sessionRef.current = next
    setSession(next)
  }, [])

  const clearCaches = useCallback(() => {
    void queryClient.cancelQueries()
    queryClient.clear()
  }, [queryClient])

  const signOut = useCallback(() => {
    attemptRef.current?.controller.abort()
    attemptRef.current = null
    // Burn the generation so a login that started before this sign-out can never apply,
    // and so the next session is never confused with this one.
    generationRef.current += 1
    applySession(null)
    clearCaches()
  }, [applySession, clearCaches])

  const signIn = useCallback(
    async (username: string, password: string) => {
      // A newer attempt supersedes any older one, which is cancelled rather than left to land.
      attemptRef.current?.controller.abort()
      const controller = new AbortController()
      generationRef.current += 1
      const generation = generationRef.current
      attemptRef.current = { generation, controller }

      const owned = () => attemptRef.current?.generation === generation

      // Ownership is held until this attempt actually finishes, and rechecked after every await:
      // cache cancellation is a second suspension point, and a sign-out or newer sign-in during it
      // must still win. Releasing ownership early let a completing login restore an ended session.
      try {
        const response = await loginRequest(username, password, controller.signal)
        if (!owned()) return                       // superseded by a newer attempt, or signed out

        await queryClient.cancelQueries()
        if (!owned()) return                       // ... and again, now that cancellation is done

        queryClient.clear()
        attemptRef.current = null
        applySession({
          generation,
          token: response.accessToken,
          userId: response.userId,
          organisationId: response.organisationId,
          displayName: response.displayName,
          role: response.role,
        })
      } catch (error) {
        // A stale failure must not surface against a newer attempt's form either.
        if (!owned()) return
        attemptRef.current = null
        throw error
      }
    },
    [applySession, queryClient],
  )

  const signOutIfCurrent = useCallback(
    (generation: number) => {
      if (sessionRef.current?.generation !== generation) return
      signOut()
    },
    [signOut],
  )

  const value = useMemo(
    () => ({ session, signIn, signOut, signOutIfCurrent }),
    [session, signIn, signOut, signOutIfCurrent],
  )
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (!value) throw new Error('useSession must be used inside SessionProvider')
  return value
}
