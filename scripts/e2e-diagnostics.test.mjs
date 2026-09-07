import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const root = fileURLToPath(new URL('../', import.meta.url))

test('a failed browser assertion publishes only allowlisted diagnostics', { timeout: 60_000 }, async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'fleet-diagnostics-test-'))
  const marker = 'RESTRICTED_DIAGNOSTIC_SENTINEL'
  try {
    const config = path.join(directory, 'playwright.config.ts')
    await writeFile(config, `
      import base from ${JSON.stringify(path.join(root, 'frontend/playwright.config.ts'))};
      export default { ...base, testDir: ${JSON.stringify(directory)},
        reporter: base.reporter.map(([file, ...options]) =>
          [${JSON.stringify(path.join(root, 'frontend'))} + '/' + file, ...options]),
        outputDir: ${JSON.stringify(path.join(directory, 'test-results'))} };
    `)
    await writeFile(path.join(directory, 'failure.spec.ts'), `
      import { test, expect } from ${JSON.stringify(path.join(root, 'frontend/node_modules/@playwright/test/index.mjs'))};
      import { writeFile } from 'node:fs/promises';
      test('deliberate diagnostics failure', async ({ page }) => {
        await page.setContent('<main>${marker}</main>');
        await writeFile(${JSON.stringify(path.join(directory, 'assertion-reached'))}, 'ready');
        console.log('${marker}');
        expect(await page.locator('main').textContent()).toBe('a different value');
      });
    `)
    const result = spawnSync(process.execPath, [
      path.join(root, 'frontend/node_modules/playwright/cli.js'), 'test', '--config', config,
    ], { cwd: root, encoding: 'utf8', timeout: 45_000, env: { ...process.env, CI: 'true' } })
    assert.equal(result.status, 1, 'the deliberately failing browser test must execute and fail')
    assert.equal(await readFile(path.join(directory, 'assertion-reached'), 'utf8'), 'ready',
      'a browser-launch failure must not count as a successful diagnostics regression')
    assert.equal(`${result.stdout}${result.stderr}`.includes(marker), false,
      'CI console output must not include assertion values or test stdout')
    const summary = JSON.parse(await readFile(path.join(directory, 'playwright-report/summary.json'), 'utf8'))
    assert.equal(summary.status, 'failed')
    assert.equal(summary.tests.length, 1)
    assert.equal(summary.tests[0].status, 'failed')
    assert.equal(JSON.stringify(summary).includes(marker), false)
    assert.deepEqual(Object.keys(summary.tests[0]).sort(), ['failureLines', 'file', 'line', 'status'])
    assert.deepEqual(summary.tests[0].failureLines, [8],
      'publish the failing assertion line, not merely the test declaration')
    assert.match(result.stdout, /failure lines: 8/)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
