import { afterEach, describe, expect, it } from 'vitest'
import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

/**
 * `make dev` previously stopped its children with `kill 0`, which signals the entire process group
 * — everything sharing it, not just what the launcher started. These tests run the launcher over
 * throwaway processes and keep an unrelated sentinel alive throughout: the sentinel surviving is
 * the point.
 *
 * They also assert that *descendants* die, because the real children (Maven, npm) each fork the
 * process that actually serves; signalling only the direct child orphans it.
 *
 * Written in JavaScript on purpose: it drives Node built-ins, and the frontend project has no
 * `@types/node`. Adding one purely to type-check a tooling test would be a new dependency.
 */
const launcher = pathToFileURL(
  path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../scripts/launcher.mjs'),
).href

/**
 * Descendants announce themselves once they are actually ready. A pid recorded by the parent right
 * after `spawn()` returns proves nothing: the child may not have run a line yet, and a SIGTERM
 * arriving first would kill it by default — making a stubborn descendant look obedient.
 */
const READY = "require('fs').writeFileSync(process.env.PROBE_PIDFILE + '.ready', 'ready');"

/** A descendant that dies on SIGTERM, like a well-behaved dev server. */
const OBEDIENT_DESCENDANT = `${READY} setInterval(() => {}, 1000)`

/** A descendant that ignores SIGTERM: only escalation to SIGKILL removes it. */
const STUBBORN_DESCENDANT = `process.on('SIGTERM', () => {}); ${READY} setInterval(() => {}, 1000)`

/**
 * Stays alive and forks a descendant, recording both pids so descendant cleanup can be checked and
 * so the probe's own processes can be reaped even if an assertion fails first.
 */
const longLived = (descendant = OBEDIENT_DESCENDANT) => `
  const { spawn } = require('child_process');
  const fs = require('fs');
  const descendant = spawn(process.execPath, ['-e', ${JSON.stringify(descendant)}], { stdio: 'ignore' });
  fs.writeFileSync(process.env.PROBE_PIDFILE, JSON.stringify({ self: process.pid, descendant: descendant.pid }));
  setInterval(() => {}, 1000);
`

/** Forks a descendant, then exits on its own — the launcher never asked it to stop. */
const exitsLeavingDescendant = (code, descendant) => `
  const { spawn } = require('child_process');
  const fs = require('fs');
  const child = spawn(process.execPath, ['-e', ${JSON.stringify(descendant)}], { stdio: 'ignore' });
  fs.writeFileSync(process.env.PROBE_PIDFILE, JSON.stringify({ self: process.pid, descendant: child.pid }));
  setTimeout(() => process.exit(${code}), 250);
`

const LONG_LIVED = longLived()
const exitsWith = (code) => `setTimeout(() => process.exit(${code}), 250);`

function isAlive(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function waitUntil(predicate, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  return predicate()
}

function exitCodeOf(child) {
  return new Promise((resolve) => child.on('exit', (code) => resolve(code)))
}

let workspace
const cleanup = []
/** Every pid this probe caused to exist, so a failing assertion cannot leak a process. */
const ownedPids = []

afterEach(() => {
  for (const child of cleanup) {
    try { process.kill(child.pid, 'SIGKILL') } catch { /* already gone */ }
  }
  for (const pid of ownedPids) {
    try { process.kill(pid, 'SIGKILL') } catch { /* already gone */ }
  }
  cleanup.length = 0
  ownedPids.length = 0
  if (workspace) rmSync(workspace, { recursive: true, force: true })
})

/** An unrelated process in its own group: nothing the launcher does may touch it. */
function startSentinel() {
  const sentinel = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'],
    { stdio: 'ignore', detached: true })
  sentinel.unref()
  cleanup.push(sentinel)
  return sentinel
}

function startLauncher(processes) {
  const specs = processes.map((p) => ({
    name: p.name,
    command: process.execPath,
    args: ['-e', p.script],
    env: p.pidFile ? { PROBE_PIDFILE: p.pidFile } : undefined,
  }))
  const harness = `
    import { launch } from ${JSON.stringify(launcher)};
    process.exit(await launch({ processes: ${JSON.stringify(specs)}, gracefulMs: 1000, log: () => {} }));
  `
  const child = spawn(process.execPath, ['--input-type=module', '-e', harness], { stdio: 'ignore' })
  cleanup.push(child)
  return child
}

/** Reads a probe's pids and registers them for cleanup the moment they are known. */
function readProbe(pidFile) {
  const probe = JSON.parse(readFileSync(pidFile, 'utf8'))
  ownedPids.push(probe.self, probe.descendant)
  return probe
}

function grandchildPid(pidFile) {
  return readProbe(pidFile).descendant
}

/** True only once the descendant has recorded its pid *and* announced it is running. */
function readyPid(pidFile) {
  try {
    readFileSync(`${pidFile}.ready`, 'utf8')
    return readProbe(pidFile).descendant > 0
  } catch {
    return false
  }
}

describe('dev launcher', () => {
  it('stops both children and their descendants when interrupted, leaving others alone', async () => {
    workspace = mkdtempSync(path.join(tmpdir(), 'dev-launcher-'))
    const backendPidFile = path.join(workspace, 'backend.pid')
    const frontendPidFile = path.join(workspace, 'frontend.pid')
    const sentinel = startSentinel()

    const launcherProcess = startLauncher([
      { name: 'backend', script: LONG_LIVED, pidFile: backendPidFile },
      { name: 'frontend', script: LONG_LIVED, pidFile: frontendPidFile },
    ])

    expect(await waitUntil(() => readyPid(backendPidFile) && readyPid(frontendPidFile))).toBe(true)
    const descendants = [grandchildPid(backendPidFile), grandchildPid(frontendPidFile)]

    process.kill(launcherProcess.pid, 'SIGINT')

    expect(await exitCodeOf(launcherProcess)).toBe(130)
    expect(await waitUntil(() => descendants.every((pid) => !isAlive(pid)))).toBe(true)
    expect(isAlive(sentinel.pid)).toBe(true)
  }, 30000)

  /**
   * A process-group leader exiting does not mean its group is empty. The launcher used to untrack
   * the group and cancel its SIGKILL escalation the moment the direct child reported exit, so a
   * descendant that ignores SIGTERM outlived gracefulMs while the launcher reported a clean stop.
   */
  it('escalates to SIGKILL for a descendant that ignores SIGTERM after its parent has exited', async () => {
    workspace = mkdtempSync(path.join(tmpdir(), 'dev-launcher-'))
    const backendPidFile = path.join(workspace, 'backend.pid')
    const sentinel = startSentinel()

    const launcherProcess = startLauncher([
      { name: 'backend', script: longLived(STUBBORN_DESCENDANT), pidFile: backendPidFile },
      { name: 'frontend', script: LONG_LIVED, pidFile: path.join(workspace, 'frontend.pid') },
    ])

    expect(await waitUntil(() => readyPid(backendPidFile))).toBe(true)
    const stubborn = grandchildPid(backendPidFile)

    process.kill(launcherProcess.pid, 'SIGINT')

    expect(await exitCodeOf(launcherProcess)).toBe(130)
    // The launcher must not report itself finished while a group it owns is still populated.
    expect(isAlive(stubborn)).toBe(false)
    expect(isAlive(sentinel.pid)).toBe(true)
  }, 30000)

  /** A child exiting unexpectedly must still have its own group cleaned up, not just its sibling's. */
  it('cleans up the descendants of a child that exits on its own', async () => {
    workspace = mkdtempSync(path.join(tmpdir(), 'dev-launcher-'))
    const backendPidFile = path.join(workspace, 'backend.pid')
    const sentinel = startSentinel()

    const launcherProcess = startLauncher([
      { name: 'backend', script: exitsLeavingDescendant(1, STUBBORN_DESCENDANT), pidFile: backendPidFile },
      { name: 'frontend', script: LONG_LIVED, pidFile: path.join(workspace, 'frontend.pid') },
    ])

    expect(await waitUntil(() => readyPid(backendPidFile))).toBe(true)
    const orphan = grandchildPid(backendPidFile)

    expect(await exitCodeOf(launcherProcess)).toBe(1)     // original failure status preserved
    expect(isAlive(orphan)).toBe(false)
    expect(isAlive(sentinel.pid)).toBe(true)
  }, 30000)

  it('stops the frontend and fails when the backend exits non-zero', async () => {
    workspace = mkdtempSync(path.join(tmpdir(), 'dev-launcher-'))
    const frontendPidFile = path.join(workspace, 'frontend.pid')
    const sentinel = startSentinel()

    const launcherProcess = startLauncher([
      { name: 'backend', script: exitsWith(1) },
      { name: 'frontend', script: LONG_LIVED, pidFile: frontendPidFile },
    ])

    expect(await waitUntil(() => readyPid(frontendPidFile))).toBe(true)
    const frontendDescendant = grandchildPid(frontendPidFile)

    expect(await exitCodeOf(launcherProcess)).toBe(1)
    expect(await waitUntil(() => !isAlive(frontendDescendant))).toBe(true)
    expect(isAlive(sentinel.pid)).toBe(true)
  }, 30000)

  it('stops the backend and preserves the status when the frontend exits non-zero', async () => {
    workspace = mkdtempSync(path.join(tmpdir(), 'dev-launcher-'))
    const backendPidFile = path.join(workspace, 'backend.pid')
    const sentinel = startSentinel()

    const launcherProcess = startLauncher([
      { name: 'backend', script: LONG_LIVED, pidFile: backendPidFile },
      { name: 'frontend', script: exitsWith(3) },
    ])

    expect(await waitUntil(() => readyPid(backendPidFile))).toBe(true)
    const backendDescendant = grandchildPid(backendPidFile)

    expect(await exitCodeOf(launcherProcess)).toBe(3)
    expect(await waitUntil(() => !isAlive(backendDescendant))).toBe(true)
    expect(isAlive(sentinel.pid)).toBe(true)
  }, 30000)
})
