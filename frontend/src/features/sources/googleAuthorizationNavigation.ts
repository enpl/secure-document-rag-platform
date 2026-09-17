/**
 * Leaves SDV for the server-issued Google authorization URL. Keeping this
 * browser side effect in one internal function lets lifecycle tests prove
 * that an obsolete account/session can never perform the late redirect.
 */
export function navigateToGoogleAuthorization(authorizationUrl: string): void {
  window.location.assign(authorizationUrl)
}
