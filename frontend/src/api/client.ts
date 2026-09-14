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

export interface ApiClient {
  get<T>(path: string): Promise<T>
  post<T>(path: string, body: unknown): Promise<T>
  del(path: string): Promise<void>
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
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const token = await getAccessToken()
    if (!token) {
      throw new ApiError(401, AUTH_REQUIRED)
    }

    const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
    if (init?.body) {
      headers['Content-Type'] = 'application/json'
    }

    let response: Response
    try {
      response = await fetch(`/api${path}`, { ...init, headers })
    } catch {
      throw new ApiError(0, NETWORK_ERROR)
    }

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

  return {
    get: (path) => request(path),
    post: (path, body) => request(path, { method: 'POST', body: JSON.stringify(body) }),
    del: (path) => request(path, { method: 'DELETE' }),
  }
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Record<string, unknown>).code === 'string' &&
    typeof (value as Record<string, unknown>).message === 'string'
  )
}
