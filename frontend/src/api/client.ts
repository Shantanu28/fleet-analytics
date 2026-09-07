import { isDashboardResponse, type DashboardResponse } from './dashboard'

/**
 * Sanitised API error. Never carries a raw response body.
 *
 * `problemType` keeps the contract's stable `urn:fleet:problem:*` identifier so the UI can branch —
 * an unknown filter needs a Reset action, a reversed range does not. It is compared against known
 * constants and never rendered; `message` is the only string that reaches the screen.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly problemType?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** The selection failures the dashboard distinguishes, exactly as `contracts/openapi.yaml` names them. */
export const PROBLEM = {
  invalidDateFormat: 'urn:fleet:problem:invalid-date-format',
  reversedDateRange: 'urn:fleet:problem:reversed-date-range',
  incompleteDateRange: 'urn:fleet:problem:incomplete-date-range',
  rangeOutsideCoverage: 'urn:fleet:problem:range-outside-coverage',
  unknownFilter: 'urn:fleet:problem:unknown-filter',
  invalidGrouping: 'urn:fleet:problem:invalid-grouping',
  unauthenticated: 'urn:fleet:problem:unauthenticated',
} as const

/** True when the caller must change the selection before any result can be produced. */
export function isSelectionProblem(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.problemType !== undefined &&
    SELECTION_PROBLEM_TYPES.has(error.problemType)
  )
}

/** True when the selection names something absent from this organisation, so Reset is the way out. */
export function isUnknownFilterProblem(error: unknown): boolean {
  return error instanceof ApiError && error.problemType === PROBLEM.unknownFilter
}

const SELECTION_PROBLEM_TYPES: ReadonlySet<string> = new Set([
  PROBLEM.invalidDateFormat,
  PROBLEM.reversedDateRange,
  PROBLEM.incompleteDateRange,
  PROBLEM.rangeOutsideCoverage,
  PROBLEM.unknownFilter,
  PROBLEM.invalidGrouping,
])

export type NamedEntity = { id: string; name: string }
export type Coverage = { dataAvailableFrom: string; dataThrough: string; revision: string }
export type Role = 'ADMIN' | 'VIEWER'

export type ContextResponse = {
  organisationName: string
  role: Role
  teams: NamedEntity[]
  repositories: NamedEntity[]
  licensedSeats: number
  coverage: Coverage
}

export type LoginResponse = {
  accessToken: string
  expiresInSeconds: number
  userId: string
  organisationId: string
  displayName: string
  role: Role
}

const GENERIC = 'Something went wrong. Please try again.'

/**
 * Messages the UI is allowed to show, chosen by the contract's stable problem `type`. The server's
 * own `detail` string is never rendered: it is attacker-influenceable in the general case and is
 * not written for this audience.
 *
 * A `Map` rather than an object literal, because the `type` is server-supplied: an object lookup
 * would resolve inherited keys such as `toString` or `__proto__` and hand back a function or an
 * object where a message is expected.
 */
const MESSAGE_BY_PROBLEM_TYPE = new Map<string, string>([
  ['urn:fleet:problem:invalid-credentials', 'Invalid username or password.'],
  ['urn:fleet:problem:invalid-request', 'Please check the details you entered and try again.'],
  ['urn:fleet:problem:unauthenticated', 'Your session has ended. Please sign in again.'],
  ['urn:fleet:problem:forbidden', 'You do not have access to this.'],
  // Each selection failure gets its own sentence: telling someone their range is reversed is
  // actionable, where "invalid input" leaves them guessing which control was wrong.
  [PROBLEM.invalidDateFormat, 'Enter both dates as YYYY-MM-DD calendar dates.'],
  [PROBLEM.reversedDateRange, 'The start date must not be after the end date.'],
  [PROBLEM.incompleteDateRange, 'Enter both a start and an end date, or use a preset.'],
  [
    PROBLEM.rangeOutsideCoverage,
    'The selected dates reach outside the range this demo dataset reports.',
  ],
  [
    PROBLEM.unknownFilter,
    'This view filters on a team or repository that is not part of this organisation’s demo dataset.',
  ],
  [PROBLEM.invalidGrouping, 'The table grouping must be either Teams or Repositories.'],
])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isNamedEntity(value: unknown): value is NamedEntity {
  return isRecord(value) && typeof value.id === 'string' && typeof value.name === 'string'
}

function isRole(value: unknown): value is Role {
  return value === 'ADMIN' || value === 'VIEWER'
}

function isCoverage(value: unknown): value is Coverage {
  return (
    isRecord(value) &&
    typeof value.dataAvailableFrom === 'string' &&
    typeof value.dataThrough === 'string' &&
    typeof value.revision === 'string'
  )
}

export function isContextResponse(value: unknown): value is ContextResponse {
  return (
    isRecord(value) &&
    typeof value.organisationName === 'string' &&
    isRole(value.role) &&
    Array.isArray(value.teams) && value.teams.every(isNamedEntity) &&
    Array.isArray(value.repositories) && value.repositories.every(isNamedEntity) &&
    typeof value.licensedSeats === 'number' &&
    isCoverage(value.coverage)
  )
}

export function isLoginResponse(value: unknown): value is LoginResponse {
  return (
    isRecord(value) &&
    typeof value.accessToken === 'string' &&
    typeof value.expiresInSeconds === 'number' &&
    typeof value.userId === 'string' &&
    typeof value.organisationId === 'string' &&
    typeof value.displayName === 'string' &&
    isRole(value.role)
  )
}

/** Reads the problem `type` to pick a controlled message; anything unrecognised stays generic. */
async function toApiError(response: Response): Promise<ApiError> {
  let problemType: unknown
  try {
    const body: unknown = await response.json()
    if (isRecord(body)) problemType = body.type
  } catch {
    problemType = undefined
  }
  const known = typeof problemType === 'string' ? problemType : undefined
  const message = known ? (MESSAGE_BY_PROBLEM_TYPE.get(known) ?? GENERIC) : GENERIC
  // The identifier is retained only when it is one this build recognises, so an unexpected value
  // cannot reach UI branching logic as though it were meaningful.
  return new ApiError(response.status, message, MESSAGE_BY_PROBLEM_TYPE.has(known ?? '') ? known : undefined)
}

/**
 * One authenticated GET, one error path. Every endpoint shares it so error parsing, body validation
 * and cancellation cannot drift between them (`.claude/rules/react.md` §5).
 */
async function authenticatedGet<T>(
  path: string,
  token: string,
  guard: (value: unknown) => value is T,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(path, {
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
  if (!response.ok) throw await toApiError(response)
  return parse(response, guard)
}

/** A 2xx whose body is not the documented shape is a failure, not a partially-usable value. */
async function parse<T>(response: Response, guard: (value: unknown) => value is T): Promise<T> {
  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new ApiError(response.status, GENERIC)
  }
  if (!guard(body)) throw new ApiError(response.status, GENERIC)
  return body
}

export async function login(
  username: string,
  password: string,
  signal?: AbortSignal,
): Promise<LoginResponse> {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
    signal,
  })
  if (!response.ok) throw await toApiError(response)
  return parse(response, isLoginResponse)
}

export async function fetchContext(token: string, signal?: AbortSignal): Promise<ContextResponse> {
  return authenticatedGet('/api/v1/analytics/context', token, isContextResponse, signal)
}

/**
 * The dashboard for one selection.
 *
 * Parameters are passed through verbatim. In particular a key present with an empty value is
 * forwarded as such, because the server distinguishes a supplied-but-blank date from an omitted one
 * and answering the wrong question would be worse than being told the input was wrong.
 */
export async function fetchDashboard(
  token: string,
  parameters: URLSearchParams,
  signal?: AbortSignal,
): Promise<DashboardResponse> {
  const query = parameters.toString()
  return authenticatedGet(
    query ? `/api/v1/analytics/dashboard?${query}` : '/api/v1/analytics/dashboard',
    token,
    isDashboardResponse,
    signal,
  )
}
