#!/usr/bin/env node
/**
 * `make e2e`: the browser journeys, against an isolated database and servers this script owns.
 *
 * The pipeline is migrate → generate types → seed → build → start servers → run → stop, and every
 * stage is pointed at the *same* explicitly chosen datasource. That matters more than it looks:
 * Flyway and jOOQ read the Maven properties `db.url`/`db.user`/`db.password`, while the application
 * and the seed launcher read the environment variables `DB_URL`/`DB_USER`/`DB_PASSWORD`. Setting
 * only one pair is the mistake that silently migrates one database and then runs against another —
 * so both are always set from one resolved value, and no stage has a fallback that could reach the
 * development database.
 *
 * Ownership is the other rule, and it is why this script starts the servers rather than letting
 * Playwright's `webServer` do it: Playwright exits on SIGTERM without stopping those children, so
 * an interrupted run would leave a JVM and a preview server holding their ports. Everything here is
 * started {@code detached}, which makes each child a process-group leader, and shutdown signals
 * only the groups this script created. It never signals process group 0, never selects a process by
 * name or by the port it holds, never runs `docker compose down`, and never removes a volume. Ports
 * are checked before use and never taken from whatever is already listening on them.
 *
 * A database supplied through `E2E_DB_URL` is migrated and seeded in place, and is never removed.
 */
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const frontendDir = path.join(repoRoot, 'frontend')

const POSTGRES_IMAGE = 'postgres:18.6-alpine'
/** Synthetic credentials for a throwaway container. Not secrets, and used nowhere else. */
const OWNED_DB = { name: 'fleet_e2e', user: 'fleet_e2e', password: 'fleet_e2e' }
const DB_READY_DEADLINE_MS = 90_000
const BACKEND_READY_DEADLINE_MS = 180_000
const PREVIEW_READY_DEADLINE_MS = 60_000
const READY_INTERVAL_MS = 500
const STOP_GRACE_MS = 10_000
const SEED_OUTCOME = /Demo seed: (INSTALLED|UNCHANGED)/
/** Server output kept for diagnosis, printed only when something fails. */
const LOG_TAIL_LINES = 60

const previewPort = process.env.FLEET_E2E_PREVIEW_PORT ?? '4183'
const apiPort = process.env.FLEET_E2E_API_PORT ?? '8091'
const apiTarget = `http://127.0.0.1:${apiPort}`
const previewTarget = `http://127.0.0.1:${previewPort}`
const playwrightArgs = process.argv.slice(2)

/** Process groups this script started, so shutdown signals only what it owns. */
const ownedGroups = new Set()
/** The container this run created, or null when an external database was supplied. */
let ownedContainer = null
/** Recent output of each owned server, for the failure path. */
const serverLogs = new Map()
let interrupted = null
let stopping = null
const containerOwner = randomBytes(16).toString('hex')

const log = (message) => console.error(`[e2e] ${message}`)
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** A failure already explained to the user; the top level need not restate it. */
function fail(message, exitCode = 1) {
  const error = new Error(message)
  error.exitCode = exitCode
  return error
}

function remember(name, chunk) {
  const lines = (serverLogs.get(name) ?? []).concat(String(chunk).split('\n'))
  serverLogs.set(name, lines.slice(-LOG_TAIL_LINES))
}

function printServerLog(name) {
  const lines = serverLogs.get(name)
  if (lines === undefined || lines.length === 0) return
  log(`last output from ${name}:`)
  process.stderr.write(`${lines.join('\n')}\n`)
}

/**
 * Spawns a child in its own process group and registers it for shutdown.
 *
 * Detached matters for more than tidiness: `./mvnw` forks a JVM, so signalling only the wrapper
 * would leave that JVM holding the port. Signalling the group reclaims the whole tree.
 */
function spawnOwned(command, args, { cwd = repoRoot, env = {}, capture = false, cleanup = false } = {}) {
  if (interrupted !== null && !cleanup) throw fail('run interrupted', interrupted)
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, ...env },
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
    detached: true,
  })
  if (child.pid !== undefined) ownedGroups.add(child.pid)
  return child
}

/** Runs one pipeline stage to completion. Output is inherited unless captured for inspection. */
export function run(name, command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawnOwned(command, args, options)
    let output = ''
    if (options.capture === true) {
      child.stdout.setEncoding('utf8')
      child.stderr.setEncoding('utf8')
      child.stdout.on('data', (chunk) => (output += chunk))
      child.stderr.on('data', (chunk) => (output += chunk))
    }

    child.on('error', (error) => {
      forgetEmptyGroup(child.pid)
      reject(fail(`${name} could not start: ${error.code ?? error.message}`))
    })
    child.on('exit', (code, signal) => {
      forgetEmptyGroup(child.pid)
      if (code === 0) return resolve(output)
      if (options.capture === true && output !== '') process.stderr.write(output)
      // The child's own exit code is preserved, so `make e2e` and CI fail for what actually failed.
      const how = signal === null ? `exit ${code}` : `signal ${signal}`
      reject(fail(`${name} failed (${how})`, code ?? 1))
    })
  })
}

function signalGroup(pgid, signal) {
  try {
    process.kill(-pgid, signal)
    return true
  } catch (error) {
    // ESRCH: already gone. EPERM: not ours to signal, which is equally a reason to stop trying.
    if (error.code === 'ESRCH' || error.code === 'EPERM') return false
    throw error
  }
}

function forgetEmptyGroup(pgid) {
  if (pgid !== undefined && !signalGroup(pgid, 0)) ownedGroups.delete(pgid)
}

/** Stops the groups this script started, escalating once, within a bounded deadline. */
export function stopOwnedProcesses() {
  if (stopping === null) stopping = stopGroups().finally(() => { stopping = null })
  return stopping
}

async function stopGroups() {
  if (ownedGroups.size === 0) return
  log(`stopping ${ownedGroups.size} owned process group(s)`)
  for (const signal of ['SIGTERM', 'SIGKILL']) {
    for (const pgid of ownedGroups) signalGroup(pgid, signal)
    const deadline = Date.now() + STOP_GRACE_MS
    while (Date.now() < deadline) {
      for (const pgid of [...ownedGroups]) if (!signalGroup(pgid, 0)) ownedGroups.delete(pgid)
      if (ownedGroups.size === 0) return
      await sleep(200)
    }
  }
  throw fail(`could not stop ${ownedGroups.size} owned process group(s)`)
}

/** Removes the container this run created — never one it was handed, and never a volume. */
async function removeOwnedContainer() {
  if (ownedContainer === null) return
  const name = ownedContainer
  ownedContainer = null
  log(`removing owned container ${name}`)
  try {
    // Registration precedes docker run so cancellation cannot lose the container. A unique label
    // proves ownership even if creation failed because the name was already occupied.
    const owner = await run('docker inspect', 'docker', [
      'inspect', '--format', '{{index .Config.Labels "fleet.e2e.owner"}}', name,
    ], { capture: true, cleanup: true })
    if (owner.trim() !== containerOwner) throw fail('container ownership label does not match')
    await run('docker rm', 'docker', ['rm', '--force', name], { capture: true, cleanup: true })
  } catch (error) {
    throw fail(`could not confirm cleanup of ${name}: ${error.message}. Inspect its ownership before removing anything.`)
  }
}

async function cleanUp() {
  try { await stopOwnedProcesses() } finally { await removeOwnedContainer() }
}

/** True when something already answers HTTP at `url` — someone else's server, or ours once up. */
async function responds(url) {
  try {
    // Any status counts, 401 included: the question is whether an HTTP server is listening.
    await fetch(url, { signal: AbortSignal.timeout(2000) })
    return true
  } catch {
    return false
  }
}

/**
 * Starts a long-lived server and waits for it to answer.
 *
 * Refuses to start when the readiness URL already answers: that is a server this script does not
 * own — most likely a developer's `make dev` — and neither reusing nor stopping it is acceptable.
 */
async function startServer(name, command, args, { cwd, env, url, deadlineMs }) {
  if (await responds(url)) {
    throw fail(
      `${name}: something is already answering at ${url}. This run will not reuse or stop it. ` +
        `Set FLEET_E2E_API_PORT / FLEET_E2E_PREVIEW_PORT to free ports and try again.`,
    )
  }

  log(`starting ${name}`)
  const child = spawnOwned(command, args, { cwd, env, capture: true })
  let exited = null
  child.stdout.setEncoding('utf8')
  child.stderr.setEncoding('utf8')
  child.stdout.on('data', (chunk) => remember(name, chunk))
  child.stderr.on('data', (chunk) => remember(name, chunk))
  child.on('error', (error) => {
    forgetEmptyGroup(child.pid)
    exited = error.code ?? 'spawn failed'
  })
  child.on('exit', (code, signal) => {
    forgetEmptyGroup(child.pid)
    exited = signal === null ? `exit ${code}` : `signal ${signal}`
  })

  const deadline = Date.now() + deadlineMs
  while (Date.now() < deadline) {
    if (interrupted !== null) throw fail('run interrupted', interrupted)
    // Checked before the readiness probe: a server that has already died will never answer, and
    // waiting out the full deadline for it wastes minutes and explains nothing.
    if (exited !== null) {
      printServerLog(name)
      throw fail(`${name} exited before becoming ready (${exited})`)
    }
    if (await responds(url)) {
      log(`${name} ready at ${url}`)
      return
    }
    await sleep(READY_INTERVAL_MS)
  }
  printServerLog(name)
  throw fail(`${name} did not become ready within ${deadlineMs / 1000}s`)
}

async function publishedPort(container) {
  const mapping = (
    await run('docker port', 'docker', ['port', container, '5432'], { capture: true })
  ).trim()
  // "127.0.0.1:32771", possibly several lines when more than one binding exists.
  const port = mapping.split('\n')[0]?.trim().split(':').pop()
  if (port === undefined || !/^\d+$/.test(port)) {
    throw fail(`could not read the published port of ${container} from "${mapping}"`)
  }
  return port
}

/** Readiness is `pg_isready` inside the container: accepting connections, not merely started. */
async function waitForDatabase(container) {
  const deadline = Date.now() + DB_READY_DEADLINE_MS
  while (Date.now() < deadline) {
    try {
      await run(
        'pg_isready',
        'docker',
        ['exec', container, 'pg_isready', '-U', OWNED_DB.user, '-d', OWNED_DB.name],
        { capture: true },
      )
      return
    } catch {
      if (interrupted !== null) throw fail('run interrupted', interrupted)
      await sleep(READY_INTERVAL_MS)
    }
  }
  throw fail(`${container} did not become ready within ${DB_READY_DEADLINE_MS / 1000}s`)
}

/**
 * The datasource every stage uses.
 *
 * A supplied `E2E_DB_URL` is a *write* target: it is migrated and seeded. Because that is
 * destructive to whatever is in it, it must be confirmed disposable explicitly — a URL alone is too
 * easy to paste from a shell history that pointed at a development database.
 */
async function resolveDatabase() {
  const supplied = process.env.E2E_DB_URL
  if (supplied !== undefined && supplied !== '') {
    if (process.env.E2E_DB_DISPOSABLE !== 'yes') {
      throw fail(
        'E2E_DB_URL is migrated and seeded in place, so it must be a disposable test database. ' +
          'Set E2E_DB_DISPOSABLE=yes to confirm, or unset E2E_DB_URL to let this run create its own.',
      )
    }
    log('using the explicitly supplied disposable database')
    log('it will be MIGRATED and SEEDED in place, and will not be removed by this run')
    return {
      url: supplied,
      user: process.env.E2E_DB_USER ?? process.env.DB_USER ?? 'fleet',
      password: process.env.E2E_DB_PASSWORD ?? process.env.DB_PASSWORD ?? 'fleet',
    }
  }

  const container = `fleet-e2e-${randomBytes(4).toString('hex')}`
  ownedContainer = container
  log(`creating owned container ${container} from ${POSTGRES_IMAGE}`)
  await run(
    'docker run',
    'docker',
    [
      'run', '--detach', '--name', container,
      '--label', `fleet.e2e.owner=${containerOwner}`,
      '--env', `POSTGRES_DB=${OWNED_DB.name}`,
      '--env', `POSTGRES_USER=${OWNED_DB.user}`,
      '--env', `POSTGRES_PASSWORD=${OWNED_DB.password}`,
      // An ephemeral loopback port: never 5432, so a developer's Compose database is untouched.
      '--publish', '127.0.0.1::5432',
      POSTGRES_IMAGE,
    ],
    { capture: true },
  )

  const port = await publishedPort(container)
  await waitForDatabase(container)
  log(`owned database ready on 127.0.0.1:${port}`)
  return {
    url: `jdbc:postgresql://127.0.0.1:${port}/${OWNED_DB.name}`,
    user: OWNED_DB.user,
    password: OWNED_DB.password,
  }
}

async function main() {
  const db = await resolveDatabase()

  // Both halves of the split, from one resolved value. Maven properties for Flyway and jOOQ:
  const mavenDb = [`-Ddb.url=${db.url}`, `-Ddb.user=${db.user}`, `-Ddb.password=${db.password}`]
  // …and environment variables for the application and the seed launcher:
  const appDb = { DB_URL: db.url, DB_USER: db.user, DB_PASSWORD: db.password }

  log('migrating and generating jOOQ types')
  await run('flyway:migrate and jOOQ generation', './mvnw', [
    '-pl', 'backend', ...mavenDb, 'flyway:migrate', 'generate-sources',
  ])

  log('installing the demo dataset')
  const seedOutput = await run(
    'seed',
    './mvnw',
    [
      '-pl', 'backend', ...mavenDb, 'spring-boot:run',
      '-Dspring-boot.run.main-class=com.fleet.analytics.seed.SeedApplication',
      '-Dspring-boot.run.profiles=seed',
    ],
    { env: appDb, capture: true },
  )
  const outcome = SEED_OUTCOME.exec(seedOutput)
  if (outcome === null) {
    process.stderr.write(seedOutput)
    throw fail('the seed reported neither INSTALLED nor UNCHANGED')
  }
  log(`seed outcome: ${outcome[1]}`)

  log('building the production frontend')
  await run('frontend build', 'npm', ['run', 'build'], { cwd: frontendDir })

  await startServer(
    'backend',
    './mvnw',
    [
      '-pl', 'backend', ...mavenDb, 'spring-boot:run',
      // Demo logins need both the profile and the flag; generated dev keys need both too.
      '-Dspring-boot.run.profiles=dev,demo',
    ],
    {
      cwd: repoRoot,
      env: {
        ...appDb,
        SERVER_PORT: apiPort,
        FLEET_DEV_KEYS: 'true',
        FLEET_DEMO_ACCOUNTS: 'true',
        // Fail-closed HMAC configuration is not weakened: a real key is supplied, generated per run
        // and never written down. Finding ids are stable within a run and differ between runs,
        // which is exactly what the journeys need and all they need.
        FLEET_FINDINGS_ID_SECRET:
          process.env.FLEET_FINDINGS_ID_SECRET ?? randomBytes(32).toString('base64'),
      },
      // An HTTP response proves the server is listening. Authenticated fixture reads subsequently
      // prove the datasource and seeded data work; an unauthenticated 401 alone cannot do that.
      url: `${apiTarget}/api/v1/analytics/context`,
      deadlineMs: BACKEND_READY_DEADLINE_MS,
    },
  )

  await startServer(
    'preview',
    'npm',
    ['run', 'preview', '--', '--port', previewPort, '--strictPort', '--host', '127.0.0.1'],
    {
      cwd: frontendDir,
      // `vite preview` inherits `server.proxy`, so this one target serves the dev and preview
      // servers alike and there is no second copy of it to drift.
      env: { FLEET_API_PROXY_TARGET: apiTarget },
      url: previewTarget,
      deadlineMs: PREVIEW_READY_DEADLINE_MS,
    },
  )

  log(`running browser journeys against ${previewTarget}`)
  try {
    await run('playwright', 'npx', ['playwright', 'test', ...playwrightArgs], {
      cwd: frontendDir,
      env: { FLEET_E2E_API_PORT: apiPort, FLEET_E2E_PREVIEW_PORT: previewPort },
    })
  } catch (error) {
    printServerLog('backend')
    throw error
  }
}

async function cli() {
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      if (interrupted !== null) return
      interrupted = signal === 'SIGINT' ? 130 : 143
      log(`${signal} received — cleaning up`)
      // The handler cannot await. Stopping the owned groups makes the current child exit, which
      // rejects main() and lets the finally block finish the cleanup properly.
      void stopOwnedProcesses().catch(error => log(error.message))
    })
  }

  let exitCode = 0
  try {
    await main()
    log('browser journeys passed')
  } catch (error) {
    exitCode = error.exitCode ?? 1
    log(error.message)
  } finally {
    try { await cleanUp() } catch (error) {
      log(error.message)
      if (exitCode === 0) exitCode = 1
    }
  }
  process.exit(interrupted ?? exitCode)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await cli()
}
