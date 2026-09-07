import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { run, stopOwnedProcesses } from './e2e.mjs'

test('shutdown reaches a SIGTERM-resistant descendant after its parent exits', { timeout: 30_000 }, async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'fleet-lifecycle-test-'))
  const readyFile = path.join(directory, 'ready.json')
  const childCode = "process.on('SIGTERM',()=>{});process.send('ready');setInterval(()=>{},1000)"
  const parentCode = `
    const child = require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(childCode)}],
      { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
    child.on('message', () => require('node:fs').writeFileSync(${JSON.stringify(readyFile)},
      JSON.stringify({ parent: process.pid, child: child.pid })));
    setInterval(()=>{},1000);
  `
  const outcome = run('lifecycle regression', process.execPath, ['-e', parentCode], { capture: true })
    .catch(error => error)
  let ids
  try {
    const deadline = Date.now() + 5000
    while (!ids && Date.now() < deadline) {
      try { ids = JSON.parse(await readFile(readyFile, 'utf8')) } catch {
        await new Promise(resolve => setTimeout(resolve, 25))
      }
    }
    assert.ok(ids, 'the owned child must start before testing shutdown')
    await stopOwnedProcesses()
    let alive = true
    try { process.kill(ids.child, 0) } catch (error) {
      if (error.code === 'ESRCH') alive = false
      else throw error
    }
    assert.equal(alive, false, 'cleanup must not forget a child when its parent exits')
  } finally {
    if (ids) {
      try { process.kill(-ids.parent, 'SIGKILL') } catch (error) {
        if (error.code !== 'ESRCH') throw error
      }
    }
    await outcome
    await rm(directory, { recursive: true, force: true })
  }
})
