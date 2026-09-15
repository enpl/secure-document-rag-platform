/**
 * M16A - thin fetch wrapper. Every call goes through Vite's dev proxy
 * (`/api` -> the backend, see vite.config.ts) so the browser only ever talks
 * same-origin - no backend CORS relaxation needed.
 *
 * Distinguishes the safe, fixed error shape the backend always returns
 * ({@link ApiErrorBody} - GlobalExceptionHandler/SecurityConfig's handlers)
 * from a network-level failure (backend/OAuth unreachable) and from an
 * unparsable response, so callers can render honest, distinct states instead
 * of a generic message for everything.
 */

export interface ApiErrorBody {
  code: string
  message: string
  traceId: string | null
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | null

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.traceId = body.traceId
  }
}

export type AccessTokenProvider = () => Promise<string | null>

/** A successful binary download - see {@link ApiClient.getBlob}. */
export interface BlobResult {
  blob: Blob
  /** Parsed from the server's `Content-Disposition` header, or null if absent/unparsable. */
  filename: string | null
}

export interface ApiClient {
  get<T>(path: string, signal?: AbortSignal): Promise<T>
  /**
   * M16C 후속 교정 - `signal`은 Token 조달(`getAccessToken`, 그 자체가
   * 비동기다 - Keycloak Refresh를 기다릴 수 있다) 이후, 실제 `fetch` Dispatch
   * 바로 직전에 다시 확인된다(그 사이 다른 `await`가 없다). 호출 시점에 이미
   * `signal.aborted`이거나 대기 도중 Abort되면, 이 요청은 Network에 전혀
   * 나가지 않고 `AbortError`로 거부된다 - 호출자가 Unmount/로그아웃/계정
   * 전환으로 이 작업을 무효화했다는 뜻이다.
   */
  post<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T>
  patch<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T>
  del(path: string): Promise<void>
  /**
   * M16C - fetches a binary response (e.g. `GET /api/shares/{shareId}/download`)
   * with the same Bearer auth as every other call - never an unauthenticated
   * anchor/URL token. A non-2xx response is parsed as the usual JSON
   * {@link ApiErrorBody} and thrown as {@link ApiError}, never handed back as
   * downloadable bytes. Pass `signal` to make the request abortable (e.g. on
   * unmount/logout/account switch) - an aborted request rejects with a
   * `DOMException` named `AbortError`, which callers should treat as "no
   * update", not as a user-facing error.
   */
  getBlob(path: string, signal?: AbortSignal): Promise<BlobResult>
}

const NETWORK_ERROR: ApiErrorBody = {
  code: 'NETWORK_ERROR',
  message: '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  traceId: null,
}

const AUTH_REQUIRED: ApiErrorBody = {
  code: 'AUTHENTICATION_REQUIRED',
  message: '로그인이 필요합니다.',
  traceId: null,
}

const UNPARSABLE_RESPONSE: ApiErrorBody = {
  code: 'INTERNAL_ERROR',
  message: '알 수 없는 오류가 발생했습니다.',
  traceId: null,
}

export function createApiClient(getAccessToken: AccessTokenProvider): ApiClient {
  async function authorizedFetch(path: string, init: RequestInit | undefined, hasJsonBody: boolean): Promise<Response> {
    const token = await getAccessToken()
    // M16C 후속 교정 - `getAccessToken()`은 그 자체로 비동기다(Keycloak
    // `updateToken`이 실제 Refresh를 수행할 수 있다). 그 대기 도중 호출자가
    // 이 작업을 이미 취소했을 수 있다(Dialog Unmount/로그아웃/계정 전환) -
    // 바로 아래 `fetch` 호출 사이에 다른 `await`를 두지 않고 여기서 즉시
    // 다시 확인한다("recheck validity ... immediately before fetch, with no
    // asynchronous gap between the final check and dispatch"). 이미
    // Abort된 Signal이면 Network에 전혀 나가지 않고 여기서 끝낸다 - Native
    // `fetch`도 이미 Abort된 Signal을 그대로 거부하지만, 이 Client는 실제
    // Fetch 호출 없이도 이를 보장한다(Test Double이 이 관례를 그대로
    // 구현하지 않아도 안전하도록).
    if (init?.signal?.aborted) {
      throw new DOMException('The operation was aborted.', 'AbortError')
    }
    if (!token) {
      throw new ApiError(401, AUTH_REQUIRED)
    }
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
    if (hasJsonBody) {
      headers['Content-Type'] = 'application/json'
    }
    try {
      return await fetch(`/api${path}`, { ...init, headers })
    } catch (error) {
      // AbortError는 사용자/호출자가 의도적으로 취소한 것이다 - Network 실패로
      // 둔갑시키지 않고 그대로 다시 던져, 호출자가 "업데이트 없음"으로 조용히
      // 처리할 수 있게 한다(getBlob의 signal 참고).
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw error
      }
      throw new ApiError(0, NETWORK_ERROR)
    }
  }

  /** 성공(2xx)이 아닌 응답을 안전한 고정 {@link ApiErrorBody} 계약으로 해석해 {@link ApiError}로 던진다. */
  async function throwForFailedResponse(response: Response): Promise<never> {
    const raw = await response.text()
    let parsed: unknown = null
    if (raw) {
      try {
        parsed = JSON.parse(raw)
      } catch {
        throw new ApiError(response.status, UNPARSABLE_RESPONSE)
      }
    }
    const body = isApiErrorBody(parsed) ? parsed : UNPARSABLE_RESPONSE
    throw new ApiError(response.status, body)
  }

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await authorizedFetch(path, init, Boolean(init?.body))

    if (response.status === 204) {
      return undefined as T
    }

    const raw = await response.text()
    let parsed: unknown = null
    if (raw) {
      try {
        parsed = JSON.parse(raw)
      } catch {
        throw new ApiError(response.status, UNPARSABLE_RESPONSE)
      }
    }

    if (!response.ok) {
      const body = isApiErrorBody(parsed) ? parsed : UNPARSABLE_RESPONSE
      throw new ApiError(response.status, body)
    }

    return parsed as T
  }

  async function getBlob(path: string, signal?: AbortSignal): Promise<BlobResult> {
    const response = await authorizedFetch(path, { method: 'GET', signal }, false)
    if (!response.ok) {
      // 성공하지 않은 응답은 항상 JSON 오류다(Controller의 ExceptionHandler 계약) -
      // 절대 그 본문을 다운로드 파일로 둔갑시키지 않는다.
      await throwForFailedResponse(response)
    }
    const blob = await response.blob()
    return { blob, filename: parseFilenameFromContentDisposition(response.headers.get('Content-Disposition')) }
  }

  return {
    get: (path, signal) => request(path, { signal }),
    post: (path, body, signal) => request(path, { method: 'POST', body: JSON.stringify(body), signal }),
    patch: (path, body, signal) => request(path, { method: 'PATCH', body: JSON.stringify(body), signal }),
    del: (path) => request(path, { method: 'DELETE' }),
    getBlob,
  }
}

/**
 * `Content-Disposition: attachment; filename="a.pdf"` 또는 RFC 5987
 * `filename*=UTF-8''%EA%B0%80.pdf` 형태를 모두 지원한다({@code
 * SharedFileDownloadController.safeFilename}이 만드는 값과 Spring
 * {@code ContentDisposition}의 실제 출력 둘 다). 파싱에 실패하면 null을
 * 반환한다 - 호출자가 안전한 기본 파일명으로 대체한다.
 */
function parseFilenameFromContentDisposition(header: string | null): string | null {
  if (!header) {
    return null
  }
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header)
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // fall through to the plain form below
    }
  }
  const plain = /filename\s*=\s*"([^"]*)"/i.exec(header)
  if (plain) {
    return plain[1]
  }
  return null
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Record<string, unknown>).code === 'string' &&
    typeof (value as Record<string, unknown>).message === 'string'
  )
}
