import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError, fetchContext } from './api/client'
import { lastCompleteUtcDate } from './api/coverage'
import { useSession, type Session } from './auth/session'

export function ContextView() {
  const { session } = useSession()
  if (!session) return null
  // Keyed by generation so a new session mounts a fresh subtree and the old one unmounts.
  return <AuthenticatedContext session={session} key={session.generation} />
}

function AuthenticatedContext({ session }: { session: Session }) {
  const { signOut, signOutIfCurrent } = useSession()

  const { data, isPending, error } = useQuery({
    // Identity, organisation, role and session generation — never the token itself (04 §5.2).
    queryKey: ['context', session.userId, session.organisationId, session.role, session.generation],
    queryFn: ({ signal }) => fetchContext(session.token, signal),
    retry: (failures, e) => !(e instanceof ApiError && (e.status === 401 || e.status === 400)) && failures < 2,
  })

  const rejected = error instanceof ApiError && error.status === 401

  // An expired or rejected token ends this session — and only this one. Signing out is a side
  // effect on external state, so it belongs in an effect rather than in render.
  useEffect(() => {
    if (rejected) signOutIfCurrent(session.generation)
  }, [rejected, signOutIfCurrent, session.generation])

  if (rejected) return null

  const completeThrough = data ? lastCompleteUtcDate(data.coverage.dataThrough) : null

  return (
    <main>
      {/* The header is always available, so signing out never depends on a request finishing. */}
      <header>
        <h1>{data ? data.organisationName : 'Fleet Analytics'}</h1>
        <p>Signed in as {session.displayName} ({session.role})</p>
        <button type="button" onClick={signOut}>Sign out</button>
      </header>

      {isPending && <p>Loading…</p>}
      {error && !rejected && <p role="alert">Could not load your organisation.</p>}

      {data && (
        <>
          <p>{data.licensedSeats} licensed {data.licensedSeats === 1 ? 'seat' : 'seats'}</p>
          <section>
            <h2>Teams</h2>
            <ul>{data.teams.map((t) => <li key={t.id}>{t.name}</li>)}</ul>
            <h2>Repositories</h2>
            <ul>{data.repositories.map((r) => <li key={r.id}>{r.name}</li>)}</ul>
          </section>
          {completeThrough && <p>Data complete through {completeThrough} (UTC).</p>}
        </>
      )}

      <p>
        Signing out clears this browser session, but <strong>does not revoke</strong> the issued token —
        it stays valid until it expires.
      </p>
    </main>
  )
}
