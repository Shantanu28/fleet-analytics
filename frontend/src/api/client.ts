/** Sanitised API error. Never carries a raw response body. */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

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
  const message =
    typeof problemType === 'string' ? (MESSAGE_BY_PROBLEM_TYPE.get(problemType) ?? GENERIC) : GENERIC
  return new ApiError(response.status, message)
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
  const response = await fetch('/api/v1/analytics/context', {
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
  if (!response.ok) throw await toApiError(response)
  return parse(response, isContextResponse)
}
