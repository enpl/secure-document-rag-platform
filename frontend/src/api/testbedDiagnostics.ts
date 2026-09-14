import type { ApiClient } from './client'

/**
 * M16A follow-up local testbed diagnostic
 * (docs/runbooks/M16A_LOCAL_TESTBED.md) - calls the backend's testbed-only,
 * double-gated endpoint (@Profile("testbed") + sdv.testbed.diagnostics.enabled).
 * Not registered at all on the canonical (non-testbed) backend - the caller
 * (components/TestbedDiagnosticPanel.tsx) treats that failure as "diagnostic
 * disabled here", never as a fake success.
 */
export interface TestbedReadDiagnosticResponse {
  success: boolean
  outcome: string
  bytesRead: number
  versionVerified: boolean
  reason: string | null
}

export function checkTestbedRead(
  client: ApiClient,
  sourceId: number,
  fileId: string,
): Promise<TestbedReadDiagnosticResponse> {
  return client.post<TestbedReadDiagnosticResponse>('/admin/testbed/diagnostics/read-check', { sourceId, fileId })
}

/**
 * M16A follow-up (safety correction) - a trivial, logic-free signal the
 * caller checks ONCE before offering the diagnostic form. Any failure
 * calling this specific endpoint means "not registered here" (it has no
 * business logic that can genuinely fail); once it succeeds, a later failure
 * from {@link checkTestbedRead} is a real error, not proof of absence - the
 * two must not be conflated (a prior version of this panel treated every
 * HTTP 500 from the read-check call as "disabled", which also hid genuine
 * runtime errors).
 */
export function checkTestbedDiagnosticsStatus(client: ApiClient): Promise<{ enabled: boolean }> {
  return client.get<{ enabled: boolean }>('/admin/testbed/diagnostics/status')
}
