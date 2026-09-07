/**
 * The dashboard request, keyed by identity.
 *
 * The key carries who is asking, which organisation, in what role, which session generation and
 * every applied parameter — never the token (04 §5.2). The generation is what stops a cached answer
 * from a previous login being served to a new one, even when the same user signs back in with the
 * same role.
 *
 * There is deliberately no `placeholderData`: showing the previous selection's numbers under the new
 * selection's labels is the one failure mode a filtered dashboard must never have (AC-07.1).
 */
import { useQuery } from '@tanstack/react-query'
import { ApiError, fetchDashboard } from '../api/client'
import type { DashboardResponse } from '../api/dashboard'
import type { Session } from '../auth/session'
import { toSearchParams, type Selection } from './selection'

export function dashboardQueryKey(session: Session, request: Selection): readonly unknown[] {
  return [
    'dashboard',
    session.userId,
    session.organisationId,
    session.role,
    session.generation,
    toSearchParams(request).toString(),
  ]
}

export function useDashboardQuery(session: Session, request: Selection) {
  return useQuery<DashboardResponse, Error>({
    queryKey: dashboardQueryKey(session, request),
    // The signal reaches fetch, so a superseded selection's request is actually cancelled.
    queryFn: ({ signal }) => fetchDashboard(session.token, toSearchParams(request), signal),
    // A rejected selection and an ended session are answers, not transient faults: retrying either
    // just repeats the same failure while the user waits.
    retry: (failures, error) =>
      !(error instanceof ApiError && (error.status === 400 || error.status === 401)) && failures < 2,
  })
}
