import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

/** Publish locations and outcomes only. Never forward errors, stdout, attachments or page content. */
export default class SafeReporter {
  tests = []
  failureLines = new WeakMap()

  onBegin(config) {
    this.directory = path.join(path.dirname(config.configFile), 'playwright-report')
  }

  onStepEnd(test, result, step) {
    // Numeric source locations are useful without exposing assertion values, step titles,
    // DOM content or tokens. Only accept lines from this test's own source file.
    if (!step.error || step.location?.file !== test.location.file) return
    const line = step.location.line
    if (!Number.isSafeInteger(line) || line < 1) return
    const lines = this.failureLines.get(result) ?? new Set()
    lines.add(line)
    this.failureLines.set(result, lines)
  }

  onTestEnd(test, result) {
    const entry = { file: path.basename(test.location.file), line: test.location.line, status: result.status }
    if (result.status !== 'passed' && result.status !== 'skipped') {
      entry.failureLines = [...(this.failureLines.get(result) ?? [])].sort((a, b) => a - b)
    }
    this.tests.push(entry)
    const detail = entry.failureLines?.length ? ` (failure lines: ${entry.failureLines.join(', ')})` : ''
    console.log(`${entry.file}:${entry.line} — ${entry.status}${detail}`)
  }

  onError() {
    console.error('Browser runner error. Inspect local diagnostics; do not publish raw errors.')
  }

  async onEnd(result) {
    await mkdir(this.directory, { recursive: true })
    await writeFile(path.join(this.directory, 'summary.json'),
      JSON.stringify({ status: result.status, tests: this.tests }, null, 2) + '\n')
    console.log(`Browser suite: ${result.status} (${this.tests.length} tests)`)
  }
}
