// Pure judgment logic for frontend/e2e/run-harness.mjs, split out so it can
// be exercised with synthetic Playwright JSON reports and synthetic
// spawnSync-shaped results in tests, without ever launching a real browser.
// Never fabricates PASS: a case that did not run, ran more than once under
// the same title, or did not pass every one of its results is reported as
// MISSING/DUPLICATE/FAIL, never silently collapsed to the first match.
//
// Deliberately imports nothing from 'node:fs' (or any other side-effecting
// module) anywhere in this file - every function here is pure path/string
// math or object inspection, so a rejection path can never, even
// transitively, reach a delete/write call. run-harness.mjs is the only place
// that touches the filesystem, and only with the already-validated path
// this file hands back.

import path from 'node:path'

// The exact format Harness.psm1 issues: `[guid]::NewGuid().ToString('N')` -
// 32 lowercase hex characters, nothing else. No dots, slashes, or separators
// of any kind can appear in a real run id, so this alone already rules out
// ".", "..", and any path-traversal attempt without needing to special-case
// them.
const RUN_ID_PATTERN = /^[0-9a-fA-F]{32}$/

/**
 * Computes the one approved report directory for this exact run id, without
 * touching the filesystem. Throws (never returns a path) for anything that
 * is not the harness's own run id format, or that would not resolve to a
 * direct child of `reportRoot`.
 * @param {string} reportRoot
 * @param {string} runId
 */
export function resolveReportDir(reportRoot, runId) {
  if (typeof runId !== 'string' || !RUN_ID_PATTERN.test(runId)) {
    throw new Error(
      'runId must be exactly the 32-hex-character token the harness itself issues (GUID "N" format) - this harness never invents its own run id.',
    )
  }
  const resolvedRoot = path.resolve(reportRoot)
  const candidate = path.resolve(resolvedRoot, runId)
  const rootWithSep = resolvedRoot.endsWith(path.sep) ? resolvedRoot : resolvedRoot + path.sep
  if (!candidate.startsWith(rootWithSep)) {
    // Should be unreachable given RUN_ID_PATTERN above - kept as an explicit,
    // independent check rather than trusting the regex alone.
    throw new Error('runId did not resolve to a direct child of the approved report root.')
  }
  return candidate
}

export function collectTests(suites, acc = []) {
  for (const suite of suites ?? []) {
    for (const spec of suite.specs ?? []) {
      for (const test of spec.tests ?? []) {
        const results = test.results ?? []
        const passed = results.length > 0 && results.every((result) => result.status === 'passed')
        acc.push({ title: spec.title, passed })
      }
    }
    collectTests(suite.suites, acc)
  }
  return acc
}

/**
 * Builds one entry per required case, PLUS one entry for every OTHER test
 * title that actually executed (e.g. a harness self-check like
 * network-isolation.spec.ts that is not one of GATE_MATRIX's 7 required
 * cases). A failure in one of those extra tests must still show up as a
 * non-PASS entry in the receipt - Harness.psm1's `Assert-AdapterReceipt`
 * rejects the whole receipt if ANY entry in `cases` is not PASS, so folding
 * every executed title in here (not only the required ones) is what makes
 * an extra test's failure actually fail the gate, instead of silently not
 * being represented at all.
 * @param {{title: string, passed: boolean}[]} executed
 * @param {string[]} requiredCases
 */
export function buildCases(executed, requiredCases) {
  const titles = new Set(requiredCases)
  for (const entry of executed) titles.add(entry.title)
  return [...titles].sort().map((id) => {
    const matches = executed.filter((entry) => entry.title === id)
    if (matches.length === 0) {
      return { id, status: 'MISSING' }
    }
    if (matches.length > 1) {
      // Never pick the first of several same-titled results - that could
      // hide a real failure sitting behind an accidental duplicate title.
      return { id, status: 'DUPLICATE' }
    }
    return { id, status: matches[0].passed ? 'PASS' : 'FAIL' }
  })
}

/**
 * Throws for an abnormal child-process outcome (failed to spawn, or killed
 * by a signal) regardless of whether a report file happens to exist on disk -
 * a signal-killed process can leave a stale/partially-written report behind,
 * and that must never be read as a real result.
 * @param {{error?: Error, signal?: string|null}} run
 */
export function assertCleanSpawn(run) {
  if (run.error) {
    throw new Error(`Playwright could not be started (spawn error: ${run.error.message}).`)
  }
  if (run.signal) {
    throw new Error(`Playwright was terminated by signal ${run.signal}, not a normal exit.`)
  }
}

/**
 * Cross-checks Playwright's own process exit status against the cases this
 * run actually collected, so neither signal alone can be trusted in
 * isolation:
 *
 * - exit 0 but some case is not PASS: fine - a case can legitimately be
 *   MISSING/DUPLICATE due to this harness's own bookkeeping even when
 *   Playwright itself exited cleanly; the non-PASS entry still fails the
 *   receipt via `Assert-AdapterReceipt`.
 * - non-zero exit and at least one case is not PASS: fine and expected -
 *   this is Playwright's normal "some test failed" exit, and the report
 *   faithfully shows which one.
 * - non-zero exit but EVERY collected case says PASS: a contradiction (e.g.
 *   a global/teardown-level error that no individual test result captured) -
 *   the report cannot be trusted, so this throws rather than emitting a
 *   receipt that would look like a clean pass.
 * @param {{status: number|null}} run
 * @param {{id: string, status: string}[]} cases
 */
export function assertConsistentOutcome(run, cases) {
  const anyNonPass = cases.some((c) => c.status !== 'PASS')
  if (run.status !== 0 && !anyNonPass) {
    throw new Error(
      `Playwright exited with status ${run.status} but every collected case reports PASS - this report cannot be trusted as a complete result.`,
    )
  }
}
