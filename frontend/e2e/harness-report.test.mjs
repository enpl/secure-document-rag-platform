#!/usr/bin/env node
// Synthetic self-tests for harness-report.mjs's judgment logic - no browser,
// no Playwright process, just fabricated report/process-result shapes. Run
// directly: `node e2e/harness-report.test.mjs`. Not part of the GATE_MATRIX
// browser adapter contract itself; a check on the adapter's own code.

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
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

let passed = 0
function check(name, body) {
  body()
  passed += 1
  process.stdout.write(`PASS ${name}\n`)
}
function rejects(name, body) {
  check(name, () => {
    assert.throws(body)
  })
}

function reportWith(tests) {
  // Shape of Playwright's own JSON reporter output, trimmed to only the
  // fields collectTests() reads.
  return { suites: [{ specs: tests.map((t) => ({ title: t.title, tests: [{ results: t.results }] })) }] }
}

const REQUIRED = ['a', 'b', 'c']

check('all-pass report yields PASS for every required case', () => {
  const report = reportWith([
    { title: 'a', results: [{ status: 'passed' }] },
    { title: 'b', results: [{ status: 'passed' }] },
    { title: 'c', results: [{ status: 'passed' }] },
  ])
  const cases = buildCases(collectTests(report.suites, []), REQUIRED)
  assert.deepEqual(cases, [
    { id: 'a', status: 'PASS' },
    { id: 'b', status: 'PASS' },
    { id: 'c', status: 'PASS' },
  ])
})

check('a failed result is reported as FAIL, never swallowed to PASS', () => {
  const report = reportWith([
    { title: 'a', results: [{ status: 'passed' }] },
    { title: 'b', results: [{ status: 'failed' }] },
    { title: 'c', results: [{ status: 'passed' }] },
  ])
  const cases = buildCases(collectTests(report.suites, []), REQUIRED)
  assert.equal(cases.find((c) => c.id === 'b').status, 'FAIL')
})

check('a skipped/interrupted/timed-out result is FAIL, not PASS', () => {
  for (const status of ['skipped', 'interrupted', 'timedOut']) {
    const report = reportWith([
      { title: 'a', results: [{ status }] },
      { title: 'b', results: [{ status: 'passed' }] },
      { title: 'c', results: [{ status: 'passed' }] },
    ])
    const cases = buildCases(collectTests(report.suites, []), REQUIRED)
    assert.equal(cases.find((c) => c.id === 'a').status, 'FAIL', `status=${status}`)
  }
})

check('a case that never ran is MISSING, never fabricated as PASS', () => {
  const report = reportWith([
    { title: 'a', results: [{ status: 'passed' }] },
    { title: 'c', results: [{ status: 'passed' }] },
  ])
  const cases = buildCases(collectTests(report.suites, []), REQUIRED)
  assert.equal(cases.find((c) => c.id === 'b').status, 'MISSING')
})

check('a duplicated title is DUPLICATE, never silently resolved to the first result', () => {
  const report = reportWith([
    { title: 'a', results: [{ status: 'passed' }] },
    { title: 'a', results: [{ status: 'failed' }] },
    { title: 'b', results: [{ status: 'passed' }] },
    { title: 'c', results: [{ status: 'passed' }] },
  ])
  const cases = buildCases(collectTests(report.suites, []), REQUIRED)
  assert.equal(cases.find((c) => c.id === 'a').status, 'DUPLICATE')
})

check('a test with zero results (never actually executed) is FAIL', () => {
  const report = reportWith([
    { title: 'a', results: [] },
    { title: 'b', results: [{ status: 'passed' }] },
    { title: 'c', results: [{ status: 'passed' }] },
  ])
  const cases = buildCases(collectTests(report.suites, []), REQUIRED)
  assert.equal(cases.find((c) => c.id === 'a').status, 'FAIL')
})

check('assertCleanSpawn accepts a normal all-pass exit', () => {
  assertCleanSpawn({ status: 0, error: undefined, signal: null })
})

check('assertCleanSpawn accepts a normal nonzero exit with real test failures (not an infra failure)', () => {
  assertCleanSpawn({ status: 1, error: undefined, signal: null })
})

rejects('assertCleanSpawn rejects a spawn error regardless of status', () => {
  assertCleanSpawn({ status: null, error: new Error('ENOENT'), signal: null })
})

rejects('assertCleanSpawn rejects a signal-killed process even if a report file exists', () => {
  assertCleanSpawn({ status: null, error: undefined, signal: 'SIGTERM' })
})

check('an extra executed test beyond the 7 required also gets a case entry', () => {
  const report = reportWith([
    { title: 'a', results: [{ status: 'passed' }] },
    { title: 'b', results: [{ status: 'passed' }] },
    { title: 'c', results: [{ status: 'passed' }] },
    { title: 'network-isolation', results: [{ status: 'failed' }] },
  ])
  const cases = buildCases(collectTests(report.suites, []), REQUIRED)
  assert.equal(cases.find((c) => c.id === 'network-isolation').status, 'FAIL')
  // The 7 required cases are unaffected by an unrelated extra test failing.
  assert.equal(cases.find((c) => c.id === 'a').status, 'PASS')
})

check(
  'assertConsistentOutcome: required 7 PASS + extra test FAIL -> no throw (nonzero exit matches a real non-PASS case)',
  () => {
    const cases = [
      { id: 'a', status: 'PASS' },
      { id: 'b', status: 'PASS' },
      { id: 'c', status: 'PASS' },
      { id: 'network-isolation', status: 'FAIL' },
    ]
    assertConsistentOutcome({ status: 1 }, cases)
  },
)

rejects('assertConsistentOutcome: all cases PASS + nonzero exit (global error) -> throws', () => {
  const cases = [
    { id: 'a', status: 'PASS' },
    { id: 'b', status: 'PASS' },
    { id: 'c', status: 'PASS' },
  ]
  assertConsistentOutcome({ status: 1 }, cases)
})

check(
  'assertConsistentOutcome: report exists + exit 0 + a MISSING case -> no throw (0 exit is internally consistent)',
  () => {
    const cases = [
      { id: 'a', status: 'PASS' },
      { id: 'b', status: 'MISSING' },
      { id: 'c', status: 'PASS' },
    ]
    assertConsistentOutcome({ status: 0 }, cases)
  },
)

check('resolveReportDir accepts the harness own 32-hex run id format', () => {
  const runId = 'a1b2c3d4e5f60718293a4b5c6d7e8f90'
  const dir = resolveReportDir(path.join(HERE, '.report'), runId)
  assert.equal(dir, path.join(HERE, '.report', runId))
})

for (const badRunId of [
  '.',
  '..',
  '../..',
  '../../../etc',
  '/abs/path',
  'C:\\Windows\\System32',
  'has/slash',
  'has\\backslash',
  '',
  'too-short',
  'contains spaces-and-more-chars!!',
  'a1b2c3d4e5f60718293a4b5c6d7e8f9', // 31 chars, one short
  'a1b2c3d4e5f60718293a4b5c6d7e8f900', // 33 chars, one long
]) {
  rejects(`resolveReportDir rejects ${JSON.stringify(badRunId)} without touching disk`, () => {
    resolveReportDir(path.join(HERE, '.report'), badRunId)
  })
}

check('harness-report.mjs never imports node:fs (rejection paths cannot reach a delete/write call)', () => {
  const codeOnly = readFileSync(path.join(HERE, 'harness-report.mjs'), 'utf8')
    .split('\n')
    .filter((line) => !line.trim().startsWith('//'))
    .join('\n')
  assert.ok(!/from\s+['"]node:fs['"]/.test(codeOnly) && !/require\(['"]node:fs['"]\)/.test(codeOnly))
})

process.stdout.write(`harness-report self-tests: ${passed} passed.\n`)
