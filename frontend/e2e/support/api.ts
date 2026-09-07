/**
 * Direct API reads, used to discover the seeded identities a journey needs.
 *
 * Nothing here asserts product behaviour: the browser does that. This exists so no test hard-codes
 * a team id, a repository id or a display name. Names are not unique across organisations — both
 * tenants could legitimately have a team called "Platform" — so every scope a test uses is resolved
 * from the identity the API returns for that tenant, and compared by id.
 *
 * The narrow types below are only what the resolution reads. They are deliberately not the
 * application's own wire types: a fixture that shares its subject's type definitions cannot notice
 * when those definitions are wrong.
 */
import type { APIRequestContext } from '@playwright/test'
import { NORTHSTAR_ADMIN, type DemoUser } from './users'

export type NamedEntity = { readonly id: string; readonly name: string }

type ContextBody = {
  readonly organisationName: string
  readonly role: string
  readonly teams: readonly NamedEntity[]
  readonly repositories: readonly NamedEntity[]
  readonly coverage: { readonly dataAvailableFrom: string; readonly dataThrough: string }
}

type FindingBody = {
  readonly id: string
  readonly ruleType: string
  readonly scopeType: string
  readonly scopeId?: string | null
  readonly scopeName: string
  readonly link: {
    readonly section: string
    readonly teamId?: string | null
    readonly repositoryId?: string | null
    readonly focusRowId?: string
    readonly grouping?: string
    readonly from?: string
    readonly to?: string
    readonly periodChanged?: boolean
  }
  readonly evidence: Record<string, unknown>
}

type DashboardBody = {
  readonly selection: { readonly from: string; readonly to: string }
  readonly comparison: {
    readonly rows: readonly { readonly scopeId: string; readonly terminalTaskCount: number }[]
  }
  readonly attention: { readonly findings: readonly FindingBody[] }
}

export async function login(request: APIRequestContext, user: DemoUser): Promise<string> {
  const response = await request.post('/api/v1/auth/login', {
    data: { username: user.username, password: user.password },
  })
  if (!response.ok()) {
    // Status only. The body of a failed login is not something to print into a CI log.
    throw new Error(`login as ${user.username} failed with status ${response.status()}`)
  }
  const body = (await response.json()) as { accessToken: string }
  return body.accessToken
}

async function authenticatedGet<T>(
  request: APIRequestContext,
  token: string,
  path: string,
): Promise<T> {
  const response = await request.get(path, { headers: { authorization: `Bearer ${token}` } })
  if (!response.ok()) throw new Error(`GET ${path} failed with status ${response.status()}`)
  return (await response.json()) as T
}

export function fetchContext(request: APIRequestContext, token: string): Promise<ContextBody> {
  return authenticatedGet<ContextBody>(request, token, '/api/v1/analytics/context')
}

export function fetchDashboard(
  request: APIRequestContext,
  token: string,
  parameters: Record<string, string>,
): Promise<DashboardBody> {
  const query = new URLSearchParams(parameters).toString()
  return authenticatedGet<DashboardBody>(request, token, `/api/v1/analytics/dashboard?${query}`)
}

/** A coverage instant as its UTC calendar day. The driver may return it in any offset. */
export function utcDate(instant: string): string {
  return new Date(Date.parse(instant)).toISOString().slice(0, 10)
}

/** The default landing range: the last 30 complete UTC days of published coverage. */
export function defaultRange(dataThrough: string): { from: string; to: string } {
  const day = 24 * 60 * 60 * 1000
  const lastComplete = new Date(Date.parse(dataThrough) - day)
  const to = lastComplete.toISOString().slice(0, 10)
  const from = new Date(lastComplete.getTime() - 29 * day).toISOString().slice(0, 10)
  return { from, to }
}

/** The identities the journeys need, all discovered rather than assumed. */
export type Scopes = {
  readonly organisationName: string
  readonly dataThrough: string
  readonly defaultFrom: string
  readonly defaultTo: string
  readonly teams: readonly NamedEntity[]
  readonly repositories: readonly NamedEntity[]
  /** The team a budget finding is raised against, and the month that finding evaluates. */
  readonly budgetedTeam: NamedEntity
  readonly budgetMonth: { readonly from: string; readonly to: string }
  /** The repository a failure spike is raised against, with the table row it focuses. */
  readonly failureSpikeRepository: NamedEntity
  /** A team whose findings include the admin-only blocked-domain finding. */
  readonly frictionTeam: NamedEntity
  /**
   * The blocked-domain finding an ADMIN sees for {@link frictionTeam}.
   *
   * The domain is here so a test can prove a VIEWER never receives it. It is compared, never
   * printed: assertions are written so a failure reports a boolean, not the value itself.
   */
  readonly frictionFinding: {
    readonly id: string
    readonly domain: string
    readonly distinctTasks: number
    readonly distinctUsers: number
  }
  /** A repository with no activity at all inside {@link budgetedTeam}. */
  readonly emptyRepositoryInBudgetedTeam: NamedEntity
  /** A day inside published coverage on which this tenant recorded nothing. */
  readonly emptyDay: string
}

function named(entities: readonly NamedEntity[], id: string | null | undefined): NamedEntity {
  const found = entities.find((entity) => entity.id === id)
  if (found === undefined) throw new Error(`the seeded dataset has no entity with id ${id}`)
  return found
}

/**
 * Resolves every scope the journeys need from the seeded dataset itself.
 *
 * Run once per worker. Each lookup states what it is looking for and fails loudly when the dataset
 * cannot supply it, so a changed dataset produces "no team has a budget finding" rather than a
 * puzzling assertion failure three files away.
 */
export async function resolveScopes(request: APIRequestContext): Promise<Scopes> {
  const token = await login(request, NORTHSTAR_ADMIN)
  const context = await fetchContext(request, token)
  const { from: defaultFrom, to: defaultTo } = defaultRange(context.coverage.dataThrough)
  const range = { from: defaultFrom, to: defaultTo, grouping: 'teams' }

  const landing = await fetchDashboard(request, token, range)

  const budget = landing.attention.findings.find((finding) => finding.ruleType === 'budget_risk')
  if (budget?.link.teamId == null || budget.link.from === undefined || budget.link.to === undefined) {
    throw new Error('no team budget finding with a navigable month is present on the landing view')
  }
  const budgetedTeam = named(context.teams, budget.link.teamId)

  const spike = landing.attention.findings.find(
    (finding) => finding.ruleType === 'task_failure_spike' && finding.scopeType === 'repository',
  )
  if (spike?.link.repositoryId == null) {
    throw new Error('no repository failure-spike finding is present on the landing view')
  }
  const failureSpikeRepository = named(context.repositories, spike.link.repositoryId)

  // The blocked-domain finding is scope-specific, so the team that raises one is searched for
  // rather than named. Sequential on purpose: one shared read-only dataset, and the first hit wins.
  let frictionTeam: NamedEntity | null = null
  let frictionFinding: FindingBody | null = null
  for (const team of context.teams) {
    const scoped = await fetchDashboard(request, token, { ...range, teamId: team.id })
    const found = scoped.attention.findings.find(
      (finding) => finding.ruleType === 'network_policy_friction',
    )
    if (found !== undefined) {
      frictionTeam = team
      frictionFinding = found
      break
    }
  }
  if (frictionTeam === null || frictionFinding === null) {
    throw new Error('no team raises a network-policy friction finding in the default range')
  }
  const { domain, distinctTasks, distinctUsers } = frictionFinding.evidence
  if (typeof domain !== 'string' || typeof distinctTasks !== 'number' || typeof distinctUsers !== 'number') {
    throw new Error('the ADMIN friction finding is missing the domain or the affected counts')
  }

  const byRepository = await fetchDashboard(request, token, {
    ...range,
    teamId: budgetedTeam.id,
    grouping: 'repositories',
  })
  const empty = byRepository.comparison.rows.find((row) => row.terminalTaskCount === 0)
  if (empty === undefined) {
    throw new Error(`every repository has activity in ${budgetedTeam.name}; no empty intersection`)
  }

  // A known empty day *inside* published coverage, so its zeros are real zeros rather than missing
  // data. The dataset opens on a deliberately empty day, but which day that is belongs to the
  // generator — so it is read back from coverage and then confirmed empty.
  const openingDay = utcDate(context.coverage.dataAvailableFrom)
  const opening = await fetchDashboard(request, token, {
    from: openingDay,
    to: openingDay,
    teamId: budgetedTeam.id,
    grouping: 'teams',
  })
  if (opening.comparison.rows.some((row) => row.terminalTaskCount > 0)) {
    throw new Error(`${openingDay} has activity in ${budgetedTeam.name}; no known empty period`)
  }

  return {
    organisationName: context.organisationName,
    dataThrough: context.coverage.dataThrough,
    defaultFrom,
    defaultTo,
    teams: context.teams,
    repositories: context.repositories,
    budgetedTeam,
    budgetMonth: { from: budget.link.from, to: budget.link.to },
    failureSpikeRepository,
    frictionTeam,
    frictionFinding: { id: frictionFinding.id, domain, distinctTasks, distinctUsers },
    emptyRepositoryInBudgetedTeam: named(context.repositories, empty.scopeId),
    emptyDay: openingDay,
  }
}
