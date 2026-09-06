#!/usr/bin/env node
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { launch } from './launcher.mjs'

/**
 * `make dev`: the backend under the dev profile and the Vite dev server, run together and stopped
 * together. It seeds nothing, changes no migrations and deletes nothing — run `make setup` first.
 */
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

process.exit(await launch({
  processes: [
    {
      name: 'backend',
      command: './mvnw',
      args: ['-pl', 'backend', 'spring-boot:run', '-Dspring-boot.run.profiles=dev'],
      cwd: repoRoot,
    },
    {
      name: 'frontend',
      command: 'npm',
      args: ['run', 'dev'],
      cwd: path.join(repoRoot, 'frontend'),
    },
  ],
}))
