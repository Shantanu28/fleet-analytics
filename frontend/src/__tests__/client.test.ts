import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchContext, login, logout } from '../api/client'

const validContext = {
  organisationName: 'Acme Engineering',
  role: 'ADMIN',
  teams: [{ id: 't1', name: 'Platform' }],
  repositories: [{ id: 'r1', name: 'acme-api' }],
  licensedSeats: 2,
  coverage: { dataAvailableFrom: '2026-01-01T00:00:00Z', dataThrough: '2026-03-01T00:00:00Z', revision: 'r' },
}

function response(body: unknown, status = 200, jsonThrows = false): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (jsonThrows) throw new SyntaxError('Unexpected token <')
      return body
    },
  } as unknown as Response
}

beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
afterEach(() => vi.unstubAllGlobals())

describe('API client', () => {
  it.each([204, 401])('logout accepts %i without requiring a JSON body', async (status) => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status }))
    await expect(logout('tok')).resolves.toBeUndefined()
  })

  it('logout does not mistake an unexpected success page for confirmed revocation', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response('<html>Proxy error</html>', { status: 200 }))
    await expect(logout('tok')).rejects.toBeInstanceOf(ApiError)
  })
  it('accepts a well-formed success body', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(validContext))
    await expect(fetchContext('tok')).resolves.toMatchObject({ organisationName: 'Acme Engineering' })
  })

  it('rejects a 200 whose body is not the documented shape', async () => {
    // licensedSeats as a string, and a role outside the enum
    vi.mocked(fetch).mockResolvedValueOnce(
      response({ ...validContext, licensedSeats: 'two', role: 'SUPERUSER' }),
    )
    await expect(fetchContext('tok')).rejects.toBeInstanceOf(ApiError)
  })

  it('rejects a 200 missing a required nested object', async () => {
    const { coverage, ...withoutCoverage } = validContext
    void coverage
    vi.mocked(fetch).mockResolvedValueOnce(response(withoutCoverage))
    await expect(fetchContext('tok')).rejects.toBeInstanceOf(ApiError)
  })

  it('rejects a 200 whose nested array items are malformed', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response({ ...validContext, teams: [{ name: 'no id' }] }))
    await expect(fetchContext('tok')).rejects.toBeInstanceOf(ApiError)
  })

  it('rejects a 200 that is not JSON at all', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(null, 200, true))
    await expect(fetchContext('tok')).rejects.toBeInstanceOf(ApiError)
  })

  it('shows a controlled message for a recognised problem type', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response({ type: 'urn:fleet:problem:invalid-credentials', status: 401, detail: 'ignored' }, 401),
    )
    await expect(login('a', 'b')).rejects.toMatchObject({
      status: 401,
      message: 'Invalid username or password.',
    })
  })

  /** The server's own `detail` must never reach the screen, however harmless it looks. */
  it('never renders server-supplied detail', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(
        { type: 'urn:fleet:problem:unknown-to-this-client', status: 500, detail: 'ORA-00942 at row 7' },
        500,
      ),
    )
    await expect(login('a', 'b')).rejects.toMatchObject({
      message: 'Something went wrong. Please try again.',
    })
  })

  /** A server-supplied `type` naming an inherited object property must not become the message. */
  it.each(['toString', '__proto__', 'constructor', 'hasOwnProperty'])(
    'shows the generic message for the unknown problem type %s',
    async (problemType) => {
      vi.mocked(fetch).mockResolvedValueOnce(
        response({ type: problemType, status: 500, detail: 'internal' }, 500),
      )
      await expect(login('a', 'b')).rejects.toMatchObject({
        message: 'Something went wrong. Please try again.',
      })
    },
  )

  it('survives a non-JSON error body', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(null, 502, true))
    const error = await login('a', 'b').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toBe('Something went wrong. Please try again.')
  })

  it('passes the abort signal through to fetch', async () => {
    const controller = new AbortController()
    vi.mocked(fetch).mockResolvedValueOnce(response(validContext))
    await fetchContext('tok', controller.signal)
    expect(vi.mocked(fetch).mock.calls[0][1]).toMatchObject({ signal: controller.signal })
  })
})
