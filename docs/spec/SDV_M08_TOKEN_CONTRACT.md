# M08 MVP OAuth and encrypted-token implementation contract

Decision date: 2026-09-13. The user approved PostgreSQL ciphertext storage with an externally supplied master key and requested MVP-first delivery with an early frontend. This fills the previous backing-store decision; do not reopen it. The detailed defaults below are coordinator-selected implementation decisions within that authorization, not claims copied from the frozen workbook.

Save this contract into `C:/workspace/secure-document-rag-platform/docs/spec/SDV_M08_TOKEN_CONTRACT.md` in the next Claude execution. The repository copy becomes authoritative for subsequent implementation. Amend only directly conflicting token/approval/workflow text in public specs and agent instructions. Do not edit the external workbook or invent official file IDs.

## Scope and delivery boundary

Implement the smallest local/single-backend-instance OAuth connection flow and production SourceTokenStore adapter. Preserve the current owner-scoped Source model: an authenticated administrator can connect their own Google account to their own Source; do not borrow an administrator's credential for another user's content request. Google API credentials stay server-side. Federation/multi-user delegation is tracked separately; do not imply it is supported.

Canonical components: F-BE-042 GoogleDriveOAuthController (in the existing source API feature), F-BE-043 GoogleTokenService and F-BE-044 GoogleTokenStoreAdapter under `com.sdv.source.infrastructure.google`, F-BE-128 SecretProperties under the existing common config package. Small concrete persistence/entity/repository/OAuth-state/DTO helpers and a necessary maintained OAuth client dependency are authorized implementation support; record actual paths in the handoff without assigning invented canonical IDs. Avoid a generic secret-provider framework.

## PostgreSQL storage and cryptography

- Use a new token-only table with an opaque random UUID reference, a unique Source FK, owner subject, format version, key ID, nonce, ciphertext including authentication tag, optimistic row version and operational timestamps. Tokens/scopes/expiry belong inside the encrypted payload; only necessary non-secret indexing/ownership metadata is plaintext.
- `source_connections.token_ref` contains only that UUID reference. Never place raw or encoded/encrypted token material in that field. Resolve using Source ID, expected owner and stored reference together; swapping a reference must fail closed.
- Serialize the current TokenEnvelope as the encrypted payload. Do not add document/evidence storage. Update the token row, Source token reference and content-free success audit in one local DB transaction. Roll back together on failure.
- Use JDK `AES/GCM/NoPadding`, a 32-byte AES key, 12-byte SecureRandom nonce freshly generated per encryption, and a 128-bit authentication tag. Never reuse a nonce/key pair. Use a documented, unambiguous AAD encoding binding format version, Source ID, owner subject, token reference and key ID. Validate lengths before decrypting. No custom cryptographic primitives.
- Runtime configuration supplies the key outside Git/DB/images, using a protected secret file as the preferred local/Compose mechanism. Preserve the existing SOURCE_TOKEN_ENCRYPTION_KEY setting as an explicit local environment alternative if useful. Require base64 decoding to exactly 32 bytes; placeholders/default/random startup keys are forbidden.
- Keep an active key ID and optional previous decryption keys. New writes use the active ID. Missing key, unknown key ID, corrupted payload, failed GCM authentication and unreadable data fail closed without revealing secrets. Do not silently overwrite unreadable credentials.
- Automatic rotation/re-encryption jobs and cloud KMS adapters are deferred. Minimum recovery is explicit administrator key configuration or reauthorization; document that losing a key requires reconnecting and that old keys must remain until dependent ciphertext is gone.
- Use the next unused Flyway version, expected V007 at the inspected V001–V006 baseline. Verify before creating it; leave existing migrations immutable. If V007 is occupied, choose the actual next free version and report it. M09 must later choose its own next free version.

## OAuth and frontend-facing contract

- Keep the canonical GET `/api/admin/sources/google/authorize` and GET `/api/admin/sources/google/callback` endpoints.
- The authorize endpoint is authenticated ADMIN-only and owner-scoped. Accept an existing owned Google Source ID; the existing Source create/list APIs provide that ID. Return a small JSON object with `authorizationUrl`, allowing the frontend to call it with its Keycloak bearer credential before navigating the browser.
- Establish unpredictable, one-use, server-side OAuth state bound to authenticated subject, Source ID, browser flow and exact configured redirect URI. Use a bounded in-memory store with a 10-minute TTL for the single-instance MVP; restart invalidates attempts and asks the user to reconnect. Bind the browser with an HttpOnly SameSite=Lax cookie; Secure outside loopback development. Use PKCE S256 where supported by the chosen maintained Google-compatible client and verify its provider behavior.
- Permit only the exact callback route through the bearer-token filter for the browser redirect, since Google will not attach a Keycloak bearer token. Validate and atomically consume state plus browser binding before any code exchange or persistence; reject missing/expired/replayed/mismatched attempts. Derive the user and Source only from validated server-side state, never callback owner/source parameters. Recheck Source ownership/status before saving. Retain ADMIN protection on all other admin routes. A denied/cancelled callback creates no credential.
- Use Google's confidential authorization-code flow, exact configured redirect URI, `drive.readonly`, offline access and current official endpoints. Do not accept browser-supplied raw access/refresh tokens. Any provider identity metadata must come from validated provider responses; SDV ownership comes from the trusted authorization initiation. Do not trust arbitrary boundSubject strings supplied by callers.
- Persist granted scopes and actual expiry; handle absent refresh tokens explicitly (preserve an existing valid refresh token only for the same verified binding, otherwise request reauthorization). Missing required scope never creates a usable connection.
- Callback returns a fixed content-free success/failure page or redirects only to a fixed configured frontend return URL, carrying a safe status code only. Never echo authorization codes, state, tokens or Google raw errors. Send no-store and no-referrer headers. Avoid putting bearer credentials in navigation URLs.
- Leave standard DTO errors/trace IDs compatible with the current API. Record the exact authorize request, callback behavior and Source connection/readiness response for the early frontend. A missing key/client configuration must yield explicit OAuth unavailable behavior while ordinary unrelated backend APIs/tests remain usable; a configured-enabled invalid setup must not silently fall back to insecure defaults.

## Refresh, deletion and transactions

- Wire the adapter into the existing SourceTokenStore Port so current catalog/content calls can obtain usable credentials. Refresh expired/near-expiry access tokens once per logical call with bounded HTTP timeouts; preserve a valid refresh token when a response omits it. Permanent invalid_grant/revocation or persistent 401 requires reauthorization, never a loop.
- Serialize a Source's token replacement/refresh/disconnect writes, consistently lock the parent Source before child token rows, and recheck active state/reference before publishing refreshed credentials. A disconnected Source must not regain a Token through a late callback/refresh. Use short, explicit transaction boundaries and bounded network calls; do not introduce Kafka/outbox work for this slice.
- SourceTokenStore.delete remains idempotent. Connect the existing disconnect path to actual local encrypted-credential cleanup; Google revocation is explicit and bounded. Do not report a Google revocation success that did not occur. If it fails, retain/report a retryable condition and preserve the existing fail-closed disconnect contract so the user can retry. Do not claim a DB rollback undoes an already executed external revocation. Emit content-free audit reasons only.

## Focused acceptance

Use synthetic data and isolated test databases: encryption roundtrip, tamper/wrong-key/wrong-Source rejection, no plaintext in persisted rows or diagnostics, missing/invalid configuration, OAuth state/browser/owner/replay/expiry validation, granted-scope handling, bounded refresh and token preservation, refresh/disconnect non-resurrection, idempotent cleanup and revocation failure. Exercise a complete mocked authorize→callback→store→connector-read flow through the actual adapter, not FakeSourceTokenStore alone. Run relevant regressions and one final Backend suite for this auth/schema change; do not repeat passing checks without a new cause.

Real Google consent/read/revoke validation needs user-managed local configuration and is tracked as UNVERIFIED until performed. It does not block the independent frontend shell. Report `OAuth/Token implementation verified with mocks; live verification pending` precisely rather than declaring live or full MVP completion.

## Technical references

The storage topology and detailed defaults above are project choices. Use the current primary documentation for protocol/library behavior:

- [Google web-server OAuth](https://developers.google.com/identity/protocols/oauth2/web-server) — consent, callback/code exchange, offline refresh and revocation.
- [JDK 21 Cipher](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html) — GCM initialization, AAD and nonce uniqueness requirements.
