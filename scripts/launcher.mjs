import { spawn } from 'node:child_process'

/** `kill(pid, 0)` performs the permission and existence check without delivering a signal. */
const EXISTENCE_CHECK = 0

/**
 * Runs several long-lived processes together and stops them as a unit.
 *
 * <p>Each child is started {@code detached}, which makes it a process-group leader, and this
 * launcher only ever signals the groups it created — by the pids it was handed back. It never
 * signals process group 0, and never selects a process by name or by the port it holds: both
 * would reach processes this launcher does not own.
 *
 * <p>Group cleanup is tracked separately from direct-child exit, because a group leader exiting
 * does not mean its group is empty. A child that forks a server and then dies — on a signal or of
 * its own accord — leaves that server behind, so escalation continues against the group until it
 * is observed empty or a bounded deadline passes.
 *
 * @returns the exit status to leave with: the first child's non-zero code if one failed,
 *          128 + signal number if the launcher itself was interrupted, otherwise 0.
 */
export function launch({
  processes,
  gracefulMs = 5000,
  hardStopMs = 5000,
  reapIntervalMs = 100,
  signals = ['SIGINT', 'SIGTERM'],
  log = console.error,
}) {
  return new Promise((resolve) => {
    /** Groups this launcher created, held until observed empty — not until their leader exits. */
    const groups = new Map()
    /** Direct children that have not yet reported exit. */
    const liveChildren = new Set()
    let outcome = null
    let settled = false
    let reaper = null

    function signalGroup(pgid, signal) {
      try {
        process.kill(-pgid, signal)          // negative pid: the group this launcher created
        return true
      } catch (error) {
        if (error.code === 'ESRCH' || error.code === 'EPERM') return false
        throw error
      }
    }

    function ensureReaper() {
      // Deliberately not unref'd: once cleanup is owed, it must keep the process alive long
      // enough to finish. It is always cleared in settle(), so it cannot hang the launcher.
      if (reaper === null) reaper = setInterval(reap, reapIntervalMs)
    }

    function terminateGroup(record) {
      if (record.terminatingSince !== null) return
      record.terminatingSince = Date.now()
      signalGroup(record.pgid, 'SIGTERM')
      ensureReaper()
    }

    function stopAllGroups() {
      for (const record of groups.values()) terminateGroup(record)
    }

    /** Escalates and reclaims owned groups. Runs on its own clock, not on child-exit events. */
    function reap() {
      const now = Date.now()
      for (const [pgid, record] of groups) {
        if (!signalGroup(pgid, EXISTENCE_CHECK)) {
          groups.delete(pgid)                                     // group is empty: reclaimed
          continue
        }
        if (record.terminatingSince === null) continue
        if (record.escalatedAt === null) {
          if (now - record.terminatingSince >= gracefulMs) {
            record.escalatedAt = now
            signalGroup(pgid, 'SIGKILL')
          }
        } else if (now - record.escalatedAt >= hardStopMs) {
          log(`[dev] gave up waiting for ${record.name}'s process group to exit`)
          groups.delete(pgid)                                     // bounded: never wait for ever
        }
      }
      settleIfDone()
    }

    function settleIfDone() {
      if (settled || liveChildren.size > 0 || groups.size > 0) return
      settled = true
      if (reaper !== null) clearInterval(reaper)
      for (const signal of signals) process.off(signal, onSignal)
      resolve(outcome ?? 0)
    }

    function onSignal(signal) {
      if (outcome === null) outcome = signal === 'SIGINT' ? 130 : 143
      stopAllGroups()
    }

    for (const signal of signals) process.on(signal, onSignal)

    for (const spec of processes) {
      const child = spawn(spec.command, spec.args ?? [], {
        cwd: spec.cwd,
        env: spec.env ? { ...process.env, ...spec.env } : process.env,
        stdio: 'inherit',
        detached: true,
      })
      liveChildren.add(child)

      child.on('error', (error) => {
        liveChildren.delete(child)
        if (outcome === null) {
          outcome = 1
          log(`[dev] ${spec.name} could not start: ${error.code ?? error.message}`)
        }
        stopAllGroups()
        settleIfDone()
      })

      if (child.pid !== undefined) {
        groups.set(child.pid, {
          name: spec.name,
          pgid: child.pid,
          terminatingSince: null,
          escalatedAt: null,
        })
      }

      child.on('exit', (code, signal) => {
        liveChildren.delete(child)
        if (outcome === null) {
          outcome = code === null ? 128 + 15 : code
          log(`[dev] ${spec.name} exited (${signal ?? code}) — stopping the others`)
        }
        // The group is deliberately left tracked: this child's own descendants may still be
        // running, and escalation against them must continue after their leader has gone.
        stopAllGroups()
        settleIfDone()
      })
    }

    if (liveChildren.size === 0) settleIfDone()
  })
}
