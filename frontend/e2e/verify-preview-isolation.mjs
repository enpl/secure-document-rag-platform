#!/usr/bin/env node
// Standalone check (not wired into any scripts/harness gate - run manually,
// e.g. `node e2e/verify-preview-isolation.mjs`). Proves the ACTUALLY RESOLVED
// Vite config - not just what vite.config.ts says on paper - has the e2e
// preview isolation active, using Vite's own `resolveConfig`, called with
// the exact same arguments (`'serve'`, mode, `'production'`, isPreview=true)
// that `vite preview`'s own internal `preview()` function uses. This is the
// same function that merges `preview.proxy ?? server.proxy` while resolving
// the config, so reading `resolved.preview.proxy` here is exactly what the
// real preview server would use - not a guess about vite.config.ts's intent.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveConfig } from 'vite'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FRONTEND_ROOT = path.resolve(HERE, '..')

function fail(message) {
  process.stderr.write(`verify-preview-isolation: ${message}\n`)
  process.exit(1)
}

// Checking only the resolved config (below) would have missed the actual
// bug this section fixes: the config itself was always correct, but
// `preview:e2e` forgot to pass `--mode e2e` at all, so it silently ran in
// default ("production") mode every time. Check the literal npm script text
// too, not only what the config would do if invoked correctly.
const pkg = JSON.parse(readFileSync(path.join(FRONTEND_ROOT, 'package.json'), 'utf8'))
for (const scriptName of ['build:e2e', 'preview:e2e']) {
  const script = pkg.scripts?.[scriptName]
  if (!script || !/--mode\s+e2e\b/.test(script)) {
    fail(`package.json script "${scriptName}" does not pass --mode e2e (got: ${JSON.stringify(script)}).`)
  }
}

const e2e = await resolveConfig({ root: FRONTEND_ROOT }, 'serve', 'e2e', 'production', true)
const normal = await resolveConfig({ root: FRONTEND_ROOT }, 'serve', 'production', 'production', true)

const problems = []

if (!(e2e.preview.proxy && Object.keys(e2e.preview.proxy).length === 0)) {
  problems.push(`e2e mode: resolved preview.proxy is not an empty object (got ${JSON.stringify(e2e.preview.proxy)}).`)
}
if (e2e.envDir !== false) {
  problems.push(`e2e mode: resolved envDir is not false (got ${JSON.stringify(e2e.envDir)}).`)
}
// The normal (non-e2e) path must be UNAFFECTED - it should still resolve to
// the real dev proxy, proving this isolation is additive, not a regression
// for `npm run dev`/`npm run build`/`npm run preview`.
if (!normal.preview.proxy || !normal.preview.proxy['/api']) {
  problems.push(
    `normal mode: resolved preview.proxy lost the real '/api' proxy entry (got ${JSON.stringify(normal.preview.proxy)}).`,
  )
}
if (normal.envDir === false) {
  problems.push(
    "normal mode: resolved envDir was unexpectedly disabled - this would break a developer's own .env.local.",
  )
}

if (problems.length > 0) {
  fail(problems.join(' '))
}

process.stdout.write(
  'verify-preview-isolation: OK - e2e mode resolves an empty preview.proxy and envDir=false; normal mode keeps the real dev proxy and env loading.\n',
)
