/**
 * The published demo logins, exactly as `README.md` documents them.
 *
 * Public synthetic credentials for a synthetic dataset — the same four the demo installer creates.
 * Nothing here is a real credential, and no test types them anywhere but this application's own
 * sign-in form.
 */
export type DemoUser = {
  readonly username: string
  readonly password: string
  readonly organisationName: string
  readonly role: 'ADMIN' | 'VIEWER'
}

export const NORTHSTAR_ADMIN: DemoUser = {
  username: 'admin',
  password: '123456',
  organisationName: 'Northstar Engineering',
  role: 'ADMIN',
}

export const NORTHSTAR_VIEWER: DemoUser = {
  username: 'viewer',
  password: 'demo-viewer-a',
  organisationName: 'Northstar Engineering',
  role: 'VIEWER',
}

export const HARBOR_ADMIN: DemoUser = {
  username: 'admin123',
  password: '1234567',
  organisationName: 'Harbor Labs',
  role: 'ADMIN',
}
