#!/usr/bin/env node
// scripts/harness gate 08's real-browser adapter contract
// (docs/harness/GATE_MATRIX.md "실제 브라우저 adapter 계약"). Invoked as
// `npm run --silent test:e2e:harness -- --run-id <UUID> --source-digest <SHA256>`.
// Prints ONLY the single required JSON object to stdout; Playwright's own
// output never reaches stdout, and no raw test output is persisted anywhere
// this script controls. Exits 0 whenever a real, complete, self-consistent
// result was produced - only an infrastructure failure (couldn't even run
// the browser, or a result that contradicts itself) exits non-zero, matching
// Harness.psm1's `Invoke-Recorded` contract (a non-zero adapter exit is
// reported as FAIL, not silently downgraded to a fabricated PASS). Judgment
// logic lives in harness-report.mjs so it can be exercised with synthetic
// reports/process results without a real browser - see harness-report.test.mjs.

import { spawnSync } from 'node:child_process'
import { existsSync, lstatSync, mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  assertCleanSpawn,
  assertConsistentOutcome,
  buildCases,
  collectTests,
  resolveReportDir,
} from './harness-report.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FRONTEND_ROOT = path.resolve(HERE, '..')

const REQUIRED_CASES = [
  'desktop-navigation',
  'mobile-focus-and-overlay',
  'long-file-list-and-sticky-controls',
  'login-bootstrap',
  'admin-conflict-reload',
  'view-only-no-download',
  'inert-output-no-external-resources',
]

function argValue(name) {
  const index = process.argv.indexOf(name)
  return index >= 0 && index + 1 < process.argv.length ? process.argv[index + 1] : undefined
}

function fail(message) {
  // Diagnostic text only, to stderr - scripts/harness never persists this,
  // and it is never mixed into the single JSON object written to stdout.
  process.stderr.write(`run-harness: ${message}\n`)
  process.exit(1)
}

function assertNotSymlink(p, label) {
  let stat
  try {
    stat = lstatSync(p)
  } catch {
    return // does not exist yet - nothing to check
  }
  if (stat.isSymbolicLink()) {
    fail(`${label} (${p}) is a symlink/junction, not a real directory - refusing to use it.`)
  }
}

const runId = argValue('--run-id')
const sourceDigest = argValue('--source-digest')
if (!runId || !sourceDigest) {
  fail('--run-id and --source-digest are required.')
}

// REPORT_ROOT is created (never deleted) if missing, then checked for a
// symlink/junction swap before being trusted with anything below it.
const REPORT_ROOT = path.join(HERE, '.report')
mkdirSync(REPORT_ROOT, { recursive: true })
assertNotSymlink(REPORT_ROOT, 'report root')

let REPORT_DIR
try {
  // Pure - never touches the filesystem itself. Rejects anything that is
  // not the harness's own 32-hex run id, or that would not land as a direct
  // child of REPORT_ROOT (e.g. ".", "..", separators, absolute paths).
  REPORT_DIR = resolveReportDir(REPORT_ROOT, runId)
} catch (error) {
  fail(error.message)
}
const REPORT_FILE = path.join(REPORT_DIR, 'results.json')

// Reject, never clean up: a pre-existing directory at this exact run id is
// either an impossible GUID collision or something this script should not
// be touching. No rmSync anywhere in this file - only mkdirSync, and only
// for a path just proven not to exist yet.
if (existsSync(REPORT_DIR)) {
  fail(`a report directory already exists for run id ${runId} - refusing to reuse or delete it; use a fresh run id.`)
}
mkdirSync(REPORT_DIR)
assertNotSymlink(REPORT_DIR, 'per-run report directory')

// Invoke the local package's own CLI entry directly with `node`, not the
// `node_modules/.bin/playwright.cmd` shim - `spawnSync` on Windows cannot
// exec a `.cmd` without `shell: true`, and this avoids needing that.
const playwrightCli = path.join(FRONTEND_ROOT, 'node_modules', '@playwright', 'test', 'cli.js')
if (!existsSync(playwrightCli)) {
  fail('local Playwright is unavailable - no automatic download of a new tool here.')
}

// Defense in depth alongside vite.config.ts's `preview.proxy = {}` under
// `--mode e2e`: even if this shell's own environment happens to carry a real
// `VITE_API_PROXY_TARGET` (e.g. a testbed launcher session in the same
// terminal), the isolated build/preview child processes never see it.
const childEnv = { ...process.env, SDV_E2E_REPORT_FILE: REPORT_FILE }
delete childEnv.VITE_API_PROXY_TARGET

// stdio: Playwright's own console/progress output is fully suppressed from
// this process's streams (never inherited), so nothing but the final JSON
// below can reach this script's real stdout.
const run = spawnSync(process.execPath, [playwrightCli, 'test', '--config', path.join(HERE, 'playwright.config.ts')], {
  cwd: FRONTEND_ROOT,
  stdio: ['ignore', 'ignore', 'ignore'],
  env: childEnv,
})

try {
  assertCleanSpawn(run)
} catch (error) {
  fail(error.message)
}

if (!existsSync(REPORT_FILE)) {
  fail(
    `Playwright produced no JSON report (adapter exit code ${run.status ?? 'unknown'}) - an infrastructure failure, not a completed test result.`,
  )
}

let report
try {
  report = JSON.parse(readFileSync(REPORT_FILE, 'utf8'))
} catch (error) {
  fail(
    `Playwright's JSON report could not be parsed (${error.message}) - treated as an infrastructure failure, never as a passing result.`,
  )
}

const executed = collectTests(report.suites, [])
const cases = buildCases(executed, REQUIRED_CASES)

try {
  assertConsistentOutcome(run, cases)
} catch (error) {
  fail(error.message)
}

process.stdout.write(JSON.stringify({ runId, sourceDigest, cases }))
process.exit(0)
