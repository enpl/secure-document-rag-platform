#!/usr/bin/env node
// Standalone check (not wired into any scripts/harness gate - run manually,
// e.g. `node e2e/verify-no-e2e-leak.mjs`, or by hand before/after touching
// vite.config.ts's e2e alias). Proves the NORMAL production bundle
// (`npm run build`, no --mode e2e) never contains the harness-only fake-auth
// double, by looking for its actual marker strings in the built JS - not by
// module count, which can match by coincidence even if the wrong module got
// bundled under a different name.

import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FRONTEND_ROOT = path.resolve(HERE, '..')
const DIST_DIR = path.join(FRONTEND_ROOT, 'dist')

const MARKERS = ['__SDV_E2E_AUTH__', 'e2e-harness-fake-token']

function fail(message) {
  process.stderr.write(`verify-no-e2e-leak: ${message}\n`)
  process.exit(1)
}

execFileSync(process.execPath, [path.join(FRONTEND_ROOT, 'node_modules', 'vite', 'bin', 'vite.js'), 'build'], {
  cwd: FRONTEND_ROOT,
  stdio: 'ignore',
})

const assetsDir = path.join(DIST_DIR, 'assets')
if (!existsSync(assetsDir)) {
  fail('production build produced no dist/assets directory.')
}

const jsFiles = readdirSync(assetsDir).filter((name) => name.endsWith('.js'))
if (jsFiles.length === 0) {
  fail('production build produced no JS bundle to inspect.')
}

const hits = []
for (const file of jsFiles) {
  const text = readFileSync(path.join(assetsDir, file), 'utf8')
  for (const marker of MARKERS) {
    if (text.includes(marker)) hits.push(`${file}: ${marker}`)
  }
}

if (hits.length > 0) {
  fail(`e2e-only marker(s) found in the production bundle: ${hits.join(', ')}`)
}

process.stdout.write(`verify-no-e2e-leak: OK - ${jsFiles.length} bundle file(s) checked, no e2e marker present.\n`)
