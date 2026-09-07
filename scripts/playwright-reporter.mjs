import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

/** Publish locations and outcomes only. Never forward errors, stdout, attachments or page content. */
export default class SafeReporter {
  tests = []

  onBegin(config) {
    this.directory = path.join(path.dirname(config.configFile), 'playwright-report')
  }

  onTestEnd(test, result) {
    const entry = { file: path.basename(test.location.file), line: test.location.line, status: result.status }
    this.tests.push(entry)
    console.log(`${entry.file}:${entry.line} — ${entry.status}`)
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
