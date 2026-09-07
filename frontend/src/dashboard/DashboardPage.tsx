/**
 * The authenticated dashboard: context, selection, one request, and the page composition.
 *
 * Two requests, two purposes. Context supplies the organisation name, the filter options and the
 * published coverage that anchors the default date range. The dashboard supplies every figure. The
 * header and filters render from context alone, so they stay usable while the dashboard request is
 * pending, failing or rejected — signing out must never depend on a request succeeding.
 */
import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  fetchContext,
  isSelectionProblem,
  isUnknownFilterProblem,
} from '../api/client'
import { lastCompleteUtcDate } from '../api/coverage'
import type { Finding, Grouping } from '../api/dashboard'
import { useSession, type Session } from '../auth/session'
import { AttentionPanel } from './AttentionPanel'
import { ComparisonTable } from './ComparisonTable'
import { DashboardHeader } from './DashboardHeader'
import { FilterBar, SelectionSummary } from './FilterBar'
import { FunnelSection } from './FunnelSection'
import { KpiRow } from './KpiRow'
import { TrendsSection } from './TrendsSection'
import './dashboard.css'
import {
  applyFindingLink,
  isCanonical,
  resetSelection,
  toRequestSelection,
  toSearchString,
  withCustomRange,
  withFilter,
  withGrouping,
  withPreset,
  type Selection,
} from './selection'
import { dashboardQueryKey, useDashboardQuery } from './useDashboardQuery'
import { useDashboardSelection } from './useDashboardSelection'

export function DashboardPage() {
  const { session } = useSession()
  if (!session) return null
  // Keyed by generation, so a new login mounts a fresh subtree and the previous one unmounts with
  // its in-flight requests. Nothing from the old session can land in the new one's view.
  return <AuthenticatedDashboard session={session} key={session.generation} />
}

/**
 * What a followed finding link needs the destination to do, once its data has arrived.
 *
 * Deliberately minimal and in memory only: a section to scroll to, a row to focus,
 * and whether the reporting period changed. No token, no domain and no copy of
 * the finding — none of which belongs in a URL, in history, or in storage.
 */
type Investigation = {
  readonly section: 'spendTrend' | 'comparisonTable' | 'attention'
  readonly focusRowId?: string
  readonly ruleType: Finding['ruleType']
  readonly periodChanged: boolean
  /** Distinguishes one follow from the next, so revealing the same destination twice works. */
  readonly sequence: number
  /**
   * The canonical query string this investigation expects, so a later filter change makes
   * it obsolete.
   *
   * Canonical, and deliberately not a serialised object: the patched selection and the same
   * selection re-read from the URL hold identical parameters in a different key order, so an
   * object comparison would never match — silently disabling every scroll, focus and notice.
   */
  readonly expectedRequest: string
}

function AuthenticatedDashboard({ session }: { readonly session: Session }) {
  const { signOut, signOutIfCurrent } = useSession()
  const { selection, push, replace } = useDashboardSelection()
  const [investigation, setInvestigation] = useState<Investigation | null>(null)
  const sequenceRef = useRef(0)
  const queryClient = useQueryClient()

  const context = useQuery({
    queryKey: ['context', session.userId, session.organisationId, session.role, session.generation],
    queryFn: ({ signal }) => fetchContext(session.token, signal),
    retry: (failures, error) =>
      !(error instanceof ApiError && (error.status === 401 || error.status === 400)) && failures < 2,
  })

  const dataThrough = context.data?.coverage.dataThrough ?? null
  const request = toRequestSelection(selection, dataThrough)
  const dashboard = useDashboardQuery(session, request)

  // The URL is made to say exactly what was requested, so the "Showing" label, the URL and the
  // response always describe one selection. Replacing rather than pushing keeps Back working:
  // a pushed canonical entry would send the user back to the non-canonical one and round again.
  useEffect(() => {
    if (dataThrough === null) return
    if (!isCanonical(selection, request)) replace(request)
  }, [dataThrough, selection, request, replace])

  const rejected =
    (context.error instanceof ApiError && context.error.status === 401) ||
    (dashboard.error instanceof ApiError && dashboard.error.status === 401)

  // Only this session ends. A 401 belonging to a superseded generation must not sign out whoever
  // happens to be signed in when it lands.
  useEffect(() => {
    if (rejected) signOutIfCurrent(session.generation)
  }, [rejected, signOutIfCurrent, session.generation])

  const apply = (next: Selection) => {
    setInvestigation(null)
    push(next)
  }

  const followLink = (finding: Finding) => {
    const next = applyFindingLink(selection, finding.link)
    const destination = toRequestSelection(next, dataThrough)

    // A friction finding's destination *recomputes* the findings for the narrower scope, and the
    // whole point of following it is to see that new result (AC-06.7). Answers are cached
    // indefinitely here, so a destination visited earlier would otherwise be served from cache and
    // its stale evidence read as the recomputed one. Dropping that entry forces a real evaluation.
    if (finding.ruleType === 'network_policy_friction') {
      queryClient.removeQueries({ queryKey: dashboardQueryKey(session, destination) })
    }

    setInvestigation({
      section: finding.link.section,
      ...(finding.link.focusRowId === undefined ? {} : { focusRowId: finding.link.focusRowId }),
      ruleType: finding.ruleType,
      periodChanged: finding.link.periodChanged === true,
      expectedRequest: toSearchString(destination),
      sequence: sequenceRef.current += 1,
    })
    push(next)
  }

  const data = dashboard.data
  const observationCutoff =
    data?.selection.observationCutoff ??
    (dataThrough === null ? null : lastCompleteUtcDate(dataThrough))

  // An investigation only applies once the destination it was created for has actually rendered.
  const requestKey = toSearchString(request)

  /**
   * Dropped, not merely hidden, once navigation leaves the destination it was created for.
   *
   * Keeping it while the URL happened not to match would let it spring back to life later — a Back
   * and a Forward to the same selection would re-scroll the page and re-show a notice about a
   * finding the user followed some time ago.
   */
  useEffect(() => {
    if (investigation !== null && investigation.expectedRequest !== requestKey) {
      setInvestigation(null)
    }
  }, [investigation, requestKey])

  // Active only once the destination's own response has rendered, so nothing moves under a
  // pending request.
  const activeInvestigation =
    investigation !== null && data !== undefined && investigation.expectedRequest === requestKey
      ? investigation
      : null
  const revealToken = activeInvestigation === null ? null : `${activeInvestigation.sequence}`

  const selectionRejected = isSelectionProblem(dashboard.error)
  const teamName =
    context.data?.teams.find((team) => team.id === selection.teamId)?.name ?? null
  const repositoryName =
    context.data?.repositories.find((repository) => repository.id === selection.repositoryId)
      ?.name ?? null

  if (rejected) return null

  return (
    <div className="page">
      <DashboardHeader
        organisationName={context.data?.organisationName ?? null}
        displayName={session.displayName}
        role={session.role}
        selection={data?.selection ?? null}
        observationCutoff={observationCutoff}
        onSignOut={signOut}
      />

      <FilterBar
        selection={selection}
        teams={context.data?.teams ?? []}
        repositories={context.data?.repositories ?? []}
        dataThrough={dataThrough}
        onPreset={(days) => {
          if (dataThrough !== null) apply(withPreset(selection, days, dataThrough))
        }}
        onCustomRange={(from, to) => apply(withCustomRange(selection, from, to))}
        onFilter={(name, id) => apply(withFilter(selection, name, id))}
        onReset={() => apply(resetSelection(dataThrough))}
        disabled={false}
      />
      <SelectionSummary
        teamName={teamName}
        repositoryName={repositoryName}
        grouping={(request.grouping as Grouping | undefined) ?? 'teams'}
      />

      {activeInvestigation?.periodChanged === true && (
        <p className="notice notice--period" role="status">
          This view uses the finding’s own reporting period, which differs from the range you had
          selected. Budgets are always evaluated over their calendar month.
        </p>
      )}

      {context.error !== null && !rejected && (
        <p className="notice notice--error" role="alert">
          Could not load your organisation.{' '}
          <button type="button" className="button" onClick={() => void context.refetch()}>
            Try again
          </button>
        </p>
      )}

      {/* A rejected selection is one failure with one cause, and it is reported once. Nothing is
          rendered beneath it: there are no metrics for a selection the server would not answer. */}
      {selectionRejected && (
        <section className="notice notice--selection" role="alert">
          <h2 className="notice__title">This selection could not be used</h2>
          <p>{(dashboard.error as ApiError).message}</p>
          {isUnknownFilterProblem(dashboard.error) && (
            <button
              type="button"
              className="button button--primary"
              onClick={() => apply(resetSelection(dataThrough))}
            >
              Reset filters
            </button>
          )}
        </section>
      )}

      {dashboard.error !== null && !selectionRejected && !rejected && (
        <section className="notice notice--error" role="alert">
          <h2 className="notice__title">Could not load the dashboard</h2>
          <p>{dashboard.error.message}</p>
          {/* Retrying keeps the current filters and URL: the request is rebuilt from the URL. */}
          <button
            type="button"
            className="button button--primary"
            onClick={() => void dashboard.refetch()}
          >
            Try again
          </button>
        </section>
      )}

      {dashboard.isPending && (
        <p className="notice notice--loading" role="status">
          Loading results for this selection…
        </p>
      )}

      {data !== undefined && (
        <>
          <KpiRow kpis={data.kpis} />
          <FunnelSection funnel={data.funnel} selection={data.selection} />
          <TrendsSection
            trends={data.trends}
            selection={data.selection}
            revealToken={activeInvestigation?.section === 'spendTrend' ? revealToken : null}
          />
          {/* The page order `00-research.md` §7 freezes: KPI row, funnel, trends, attention, table. */}
          <AttentionPanel
            attention={data.attention}
            role={session.role}
            recalculated={
              activeInvestigation !== null &&
              activeInvestigation.ruleType === 'network_policy_friction'
            }
            revealToken={activeInvestigation?.section === 'attention' ? revealToken : null}
            onFollow={followLink}
          />
          <ComparisonTable
            comparison={data.comparison}
            onGrouping={(grouping) => apply(withGrouping(selection, grouping))}
            focusRowId={
              activeInvestigation?.section === 'comparisonTable'
                ? (activeInvestigation.focusRowId ?? null)
                : null
            }
            revealToken={activeInvestigation?.section === 'comparisonTable' ? revealToken : null}
          />
        </>
      )}

      <footer className="page-footer">
        <p>
          Every figure on this page comes from synthetic demo data. Signing out clears this browser
          session and revokes its token on the server. Other signed-in sessions are unaffected.
        </p>
      </footer>
    </div>
  )
}

/**
 * Brings a followed finding's destination into view, exactly once per navigation.
 *
 * Keyed on a token rather than a boolean, so following the same finding twice reveals the
 * destination twice — a boolean stays true and would silently do nothing the second time.
 *
 * `token === null` covers every ordinary render: a filter change, a background rerender or a
 * pending request must never move the page or take focus away from what the user is doing. The
 * reveal only happens when an investigation is active *and* its destination has rendered.
 *
 * @param focusTarget when true, the element also receives focus, so a keyboard or screen-reader
 *   user arrives at the destination rather than being left at the link they activated. Requires the
 *   element to be focusable (`tabIndex={-1}` on a section is enough).
 */
export function useReveal(
  token: string | null,
  focusTarget = false,
): (node: HTMLElement | null) => void {
  const nodeRef = useRef<HTMLElement | null>(null)
  const revealed = useRef<string | null>(null)

  useEffect(() => {
    const node = nodeRef.current
    if (token === null || node === null || revealed.current === token) return
    revealed.current = token
    node.scrollIntoView({ block: 'start', behavior: 'auto' })
    // preventScroll: the scroll above already positioned it; focusing would otherwise fight it.
    if (focusTarget) node.focus({ preventScroll: true })
  }, [token, focusTarget])

  return (node: HTMLElement | null) => {
    nodeRef.current = node
  }
}
