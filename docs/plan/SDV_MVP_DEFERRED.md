# SDV MVP 우선 구현 · 보완 목록

2026-09-13 사용자 지시로 도입. 목적은 동작하는 MVP와 프론트엔드를 빠르게 완성하면서 미완료 사항을 잊지 않는 것이다. 문서 작성은 해당 기능의 구현이나 테스트 완료를 의미하지 않는다.

## 문서 위치와 관리

현재 이 파일은 Codex 관제의 원본이다. 다음 Claude 작업에서 동일 내용을 `C:/workspace/secure-document-rag-platform/docs/plan/SDV_MVP_DEFERRED.md`로 저장한다. 저장소 파일이 생성된 뒤에는 저장소 파일을 최신 원본으로 읽고 갱신하며, 이 outputs 파일은 초기 스냅샷으로 취급한다. 이전 기록을 지우지 말고 상태와 근거를 갱신한다.

Codex와 Claude는 매 작업 시작에 최신 보완 목록, 현재 Git 상태, latest.md를 먼저 읽는다. Claude는 여전히 `docs/workmd/**`에 접근하지 않는다.

## 실행 규칙

- 현재 작업에 꼭 필요한 기능과 실제로 실패하는 권한/비밀/데이터 무결성 문제를 우선 처리한다. 기능의 직접 테스트가 통과하면 해당 범위를 마친다.
- 단순 코드 취향, 추상화, 모든 가능한 예외의 선제 대응, 전 문서 재정렬은 완료를 막지 않는다.
- 이미 승인된 범위의 작은 구현 선택은 담당 에이전트가 결정하고 이유만 기록한다. 같은 설계를 반복 승인받지 않는다.
- 보완 항목의 선행 조건이 충족됐고 작은 변경으로 끝낼 수 있으면, Codex가 작업 시작 시 항목 ID·효과·수정 범위·검증·추가 비용을 한 번 제시해 사용자에게 이번에 포함할지 묻는다. 사용자가 수락하기 전에는 보완 항목을 자동으로 끼워 넣지 않는다. 기존 승인 범위의 필수 수정에는 적용하지 않는다.
- 실행자는 보완 후보를 발견하면 목록과 handoff에 남기고 승인된 작업을 계속한다. 동일한 후보를 상황 변화 없이 매번 다시 묻지 않는다.
- 직접 영향 테스트 우선. 공통 인증/스키마/토큰 경계 변경이면 Backend 회귀를 마무리 시 한 번 실행한다. 문서만 바뀐 작업은 빌드하지 않는다. 통과 후 새 변경·실패·구체적 우려가 없으면 반복하지 않는다.
- 상태를 `NOW`, `NEXT`, `BEFORE_MVP`, `BEFORE_DEPLOYMENT`, `LATER`, `CLOSED`로 구분한다. 미검증은 PASS로 계산하지 않는다.
- 단계별 합격은 범위 한정이다. UI나 Mock 테스트가 끝나도 전체 RAG/실제 Drive 완료라고 표시하지 않는다.
- 한 번에 실행 프롬프트 하나. Git 변경은 사용자만 수행하고 실행 전에 필요한 브랜치 작업을 안내한다. 매 작업 종료에 latest.md를 누적 갱신하고 다시 연다.

## 승인된 진행 순서

1. 현재 M08 브랜치에서 최소 OAuth 연결 + PostgreSQL 암호문 Token 저장 + 갱신/해제를 구현한다.
2. **M16의 프론트엔드 기반 부분을 앞당긴다.** 로그인, Source 목록/Google 연결/해제, 준비 상태부터 구현한다. M09~M15 전체 완료를 기다리지 않는다.
3. M09/M10의 동기화·메타데이터 검색이 준비될 때 문서 목록/검색 화면을 실제 API에 연결한다.
4. M11~M14의 색인·현재 권한 재확인·답변·출처 흐름을 화면에 순차 연결한다. M13의 요청 범위/Prompt 보안은 실제 질문 기능 공개 전에 적용한다.
5. 필요한 감사(M15), 실제 계정/화면 검증(M17), MVP 최종 판정(M18)을 완료한다. 고급 관리 화면은 먼저 만들 필요가 없다.

이 순서는 UI 개발 시점을 앞당긴 것이며 전체 기능 의존성이나 v1.4의 보안 계약을 삭제하지 않는다. 기존 문서의 M19/M20 검증 표기는 최신 M17/M18 체계보다 오래된 기록이다.

## 항목별 기록

| ID | 상태 | 항목 / 현재 증거 | 재검토 시점과 완료 기준 | 지금 미루는 영향 |
|---|---|---|---|---|
| MVP-01 | CLOSED | PostgreSQL 암호문 Token 저장 + 외부 주입 키. **구현 완료(Mock/Testcontainers 검증)** - `GoogleDriveOAuthController`/`GoogleDriveOAuthService`/`GoogleTokenService`/`GoogleTokenStoreAdapter`/`GoogleOAuthClient`/`GoogleOAuthStateStore`, `V007__oauth_token_store.sql`. 암호화 Round-Trip, 변조/Key 불일치/Source Reference 바꿔치기 Fail Closed, 평문 비저장, OAuth State Replay/Browser 결합/Scope 검증, Bounded Refresh, Revoke 멱등성/실패 보존을 실제 Testcontainers PostgreSQL + Local Mock HTTP Server로 검증(`GoogleTokenServiceTest` 13개, `GoogleDriveOAuthServiceTest` 7개, `GoogleOAuthStateStoreTest` 7개, 전부 통과) | 없음(구현 종결) - 실제 Google 계정 연결 자체는 MVP-03이 별도 추적한다 | 코드/DB/Mock 검증은 끝났다. 실제 계정 연결 여부는 여전히 MVP-03 완료 전까지 미검증 |
| MVP-02 | NOW | 프론트엔드는 React/Vite 기본 화면. 로그인·Drive 연결·Source 목록 먼저 | MVP-01이 끝나 M08 최소 API(`GET /api/admin/sources/google/authorize`, `GET /api/admin/sources/google/callback`, 기존 `GET/POST/DELETE /api/admin/sources`)가 준비됐다 - 즉시 착수 가능. 기존 API는 실연결, 미구현 API는 준비 중/명시적 데모 표시 | 백엔드 전체를 기다리지 않음. 현재 `GET /api/admin/sources` 응답(`SourceResponse`: id/type/status/lastSyncAt)은 Google Credential 실제 존재 여부를 구분하지 못한다 - Frontend가 "연결됨"을 정직하게 표시하려면 작은 API 확장이 필요할 수 있다(아래 MVP-17) |
| MVP-03 | BEFORE_MVP | 실제 Google OAuth/읽기/해제 Smoke Test 미실행 | 연결 UI와 로컬 설정 준비 시 사용자 계정으로 최소 문서 1개 검증. 실제 자격증명은 사용자 관리 | MVP-01의 코드/Mock 검증은 끝났다 - 지금 UI 개발/코드 검증은 진행 가능. Live 완료 주장은 여전히 불가 |
| MVP-04 | BEFORE_MVP | 전체 질문→현재 사용자 권한 확인→답변·출처 및 임시 근거 만료 미구현 | M11~M14/M17에서 실제 한 경로 완주, 권한 회수·버전 변경·근거 없음·취소 검증 | 화면 틀은 가능, 실제 답변 기능 완료는 불가 |
| MVP-05 | BEFORE_MVP | 현재 Source 소유자 전용 자격증명 결합. 다른 사용자 계정 매핑/DWD 없음 | 인증 UI 통합 전에 제품의 사용자/Source 권한 흐름 확인. 다른 사용자는 자기 자격증명 또는 명시적 미지원. 공동 Source 다사용자 시나리오 필요 시 별도 구현 | 현재 소유자 흐름 개발은 가능. 다른 사용자에 대한 owner-token 대리 사용 금지 |
| MVP-06 | CLOSED | Refresh/재연결과 disconnect 경합의 삭제 후 Token 재생성 방지(M08 자체 Writer 범위) | **M08 Writer(Refresh/최초인증/Disconnect)끼리의 경합은 실제로 동시성 테스트로 검증 완료.** `GoogleTokenService.store/revoke/publishRefreshIfStillCurrent`가 항상 부모 `source_connections`를 먼저 잠그고(`findByIdForUpdate`/`lockAndReadCurrentOwnershipState`), Refresh 발행은 `source_oauth_tokens.row_version`으로 세대(Generation)까지 확인한다. `SourceTokenConcurrencyTest`(신규, 실제 Proxied Service + 독립 Commit Transaction + Latch, Sleep 없음) 2개 - (1) Refresh가 Google 응답을 기다리는 동안 실제 `disconnect()`가 별도 Transaction으로 완전히 Commit되는 경우, (2) 최초 인증(Callback)이 응답을 기다리는 동안 Token이 전혀 없던 Source가 실제로 `disconnect()`되는 경우 - 둘 다 수정 전 코드에서 실제로 실패함을 확인한 뒤 수정 후 통과를 확인했다(Revert-Rerun-Restore로 직접 검증, 추측이 아니다) | M08 범위 안에서는 재실행 불필요(반복 요청 금지). **M11의 색인(Embedding) Writer와 disconnect의 교차 검증은 이것과 다른, 아직 열려있는 별도 항목이다 - 아래 MVP-18 참고, 여기 CLOSED에 포함되지 않는다.** |
| MVP-18 | LATER | M11 색인(Embedding) Writer와 disconnect의 교차 경합 검증 미실시 - M11 자체가 아직 존재하지 않는다 | M11 RAG Ingestion Orchestration이 실제로 `DocumentEmbeddingJpaRepository.replaceGeneration` 등을 호출하는 Writer를 만들 때, 그 Writer와 `SourceConnectionService.disconnect`의 동시 실행을 `SourceTokenConcurrencyTest`와 같은 방식(실제 Proxied Service + Latch)으로 검증한다 | MVP-06(M08 Writer끼리의 경합)과 혼동하지 않는다 - MVP-06은 이미 CLOSED, 이 항목은 M11이 생기기 전까지는 테스트 대상 자체가 없다 |
| MVP-07 | LATER | 권한 페이지 10,000 상한 직접 경계 테스트 미실행. 반복/순환은 통과 | 페이지 로직을 다음 수정할 때 작은 주입 상한으로 테스트 가능한지 제안. 현재 MVP 진행의 별도 차단 사유 아님 | 정상/순환 경로는 검증됨. 상한에서의 실패 동작 확인은 남음 |
| MVP-08 | BEFORE_MVP | Drive changeType/공유 드라이브 실제 응답 범위와 현재 file 중심 DTO | M09 Change 소비 시작 때 공식 API와 범위 대조. 공유 드라이브 이벤트를 파일 삭제로 오인하지 않고 안전하게 진행 | 현재 Mock 파일 이벤트 검증만으로 전체 Drive Sync 완료 주장 불가 |
| MVP-09 | BEFORE_DEPLOYMENT | V006 이후 과거 평문 데이터의 WAL/백업/디스크 물리적 폐기 실환경 증거 없음 | 실제 데이터 사용/배포 전 기존 V006 runbook 수행. 적용 이력·잔여 백업 범위를 증거로 남김 | 합성 개발 데이터로 UI/기능 개발 가능. 이미 민감 데이터가 있다면 즉시 상향 |
| MVP-10 | BEFORE_DEPLOYMENT | 자동 Key rotation / bulk re-encryption 도구 없음 | 기본은 active key ID·기존 키 해독·재연결 복구. 실제 고객 Token 보관 배포 전에 교체/분실/복구 절차 검증 | MVP 자동 스케줄러는 불필요. 키를 버려 기존 Token을 못 읽게 하는 운영은 금지 |
| MVP-11 | LATER | AWS Secrets Manager/KMS 또는 외부 Vault Adapter | P01/P02 등 배포 제공자가 정해질 때 기존 SourceTokenStore 경계 활용 | 로컬 MVP에 추가 서비스 없음 |
| MVP-12 | LATER | Google 대형 Workspace 문서 장시간 export/download | 실제 요구가 생길 때. 현재 한도 초과는 명시적 EXPORT_LIMIT_EXCEEDED | 큰 문서 처리 기능 제한. 잘린 원문 사용 금지 |
| MVP-13 | LATER | 고급 관리자 대시보드·일괄 운영 UX·미세 성능 최적화 | 핵심 사용자 흐름 완성 후 측정/요구에 따라 제안 | 기본 보안 검사와 서버 감사는 유지 |
| MVP-14 | BEFORE_DEPLOYMENT | 타 PC 접근·TLS·방화벽·프록시·멀티 인스턴스 OAuth state 운영 | 로컬 로그인/연결/질문이 동작한 뒤 노출 환경 확정 시 | 로컬 개발을 막지 않음. 네트워크 공개 전 검증 필요 |
| MVP-15 | LATER | 전체 명세 진행 상태/Javadoc 정렬 | 직접 수정하는 구간의 치명적 모순만 즉시, 전체 정렬은 안정화 때 | 과거 M07A 대기·100:1 승인 대기 문구를 실제 현재 상태로 오인하지 않음 |
| MVP-16 | CLOSED | M06 옛 lock-order 보정 | M07A에서 해당 평문 저장 경로 제거, 사용자 no-op 종결 | 반복 실행 금지 |
| MVP-17 | LATER | `GET /api/admin/sources`(`SourceResponse`)가 Google Credential 실제 존재 여부(`source_oauth_tokens` 행이 있는지)를 노출하지 않는다 - `status`(ACTIVE/DISABLED)만으로는 "생성됐지만 아직 연결 안 됨"과 "연결됨"을 구분할 수 없다 | Frontend(MVP-02) 세션이 Source 목록/연결 화면을 만들 때, 이 구분이 실제로 필요하면 이 작은 API 확장(`SourceResponse`에 `credentialPresent`류 필드 추가)을 요청한다 - 지금 이 작업(M08 Backend)에서 미리 추가하지 않는다(Frontend 구현은 이번 작업 범위 밖). **2026-09-13 정정**: 이 구현은 `SourceApiMapper`가 `SourceTokenStore.load`를 직접 호출해 계산하면 안 된다 - `GoogleTokenService.load`는 이제 부작용이 있다(만료 시 실제 Google Refresh 호출 + 실제 재암호화 DB 쓰기까지 수행한다). 단순 목록 조회(Read-Only API)가 매번 이를 트리거하면 안 된다 - 실제 구현 시에는 `source_oauth_tokens`에 해당 `source_id` 행이 존재하는지만 보는 부작용 없는(Side-Effect-Free) 별도 조회(예: 간단한 `existsBySourceId` 류)를 새로 만들어야 한다. `SourceTokenStore.load()`를 재사용하지 않는다 | 현재는 Callback 성공/실패 Redirect(`?googleConnect=success|failed`) 또는 고정 Content-Free 응답 페이지로만 연결 성공 여부를 알 수 있다 - Source 목록 화면 자체의 "연결됨" 표시는 이 확장 전까지 부정확할 수 있다 |
| MVP-19 | BEFORE_DEPLOYMENT | 이전(Previous) Key로 암호화된 행의 복호화 자체가 코드에 없다 - `GoogleTokenService.resolveKeyForRowOrFail`은 저장된 `key_id`가 지금 설정된 활성 Key와 다르면 무조건 Fail Closed 한다(MVP-10의 "자동 Rotation 도구 없음"과는 다른, 더 근본적인 사실: 도구가 있어도 없어도 여러 Key를 동시에 읽는 경로 자체가 없다) | 실제 Key 교체(Rotation) 절차를 설계할 때, "이전 Key로 이미 암호화된 기존 행을 읽는" 경로를 명시적으로 추가할지(MVP-10과 함께), 아니면 교체 시 전량 재연결을 요구할지 결정한다 | 지금은 활성 Key 하나만 있다고 가정해도 MVP 진행에 지장 없음 - Key를 바꾸면 기존 모든 연결이 재인증 필요 상태가 된다는 사실만 운영 문서에 남는다 |
| MVP-20 | LATER | `GoogleOAuthStateStore`(진행 중인 OAuth 시도의 In-Memory 저장소)가 TTL(10분)과 "새 시도 등록 시 만료분 청소"만 있고, 저장 가능한 총 개수 상한(Count Cap)은 없다 | 실제 배포 전 대량 동시 미완료 Authorize 요청(예: 봇/공격성 트래픽)으로 인한 메모리 증가 가능성을 측정하고, 필요하면 최대 개수를 두거나 Bounded Map으로 교체한다 | 로컬/개발 단일 사용자 사용에는 지장 없음 - 배포 전 재검토 필요 |
| MVP-21 | BEFORE_MVP | HTTP Controller/Browser Cookie(`GoogleDriveOAuthController`의 실제 Set-Cookie/Redirect/503 응답)와 실제 Google 상대의 왕복은 이번 작업들에서 `GoogleDriveOAuthService`/`GoogleTokenService` Application/Service 계층의 직접 호출로만 검증됐다 - 실제 `MockMvc`/HTTP 수준 Controller 테스트나 실제 Google 계정 검증은 아직 없다 | MVP-03(실제 Google Smoke Test)과 함께, 또는 Frontend 연동 직전에 최소 Controller-Level(Cookie 발급/제거, 503 OAUTH_UNAVAILABLE 응답 형태) 테스트를 추가할지 판단한다 | Application/Service 계층 로직 자체는 이미 충분히 검증됨 - HTTP 경계/실제 Cookie 동작/실제 Google 왕복만 미검증으로 남아있다 |

새 항목을 추가할 때는 발견 근거, 실제 사용자 영향, 다음 판단 시점, 완료 증거, 최근 판단/보류 이유를 함께 적는다. 번호를 과거 기능 ID로 오인하지 않는다. 이 ID들은 작업 추적용이며 공식 File/Feature ID가 아니다.

## 현재 검증 스냅샷

- 브랜치 `feat/m08-google-drive-connector`, HEAD `59151c3`(변경 없음 - 모든 변경은 여전히 미커밋 Working Tree), 미커밋 61개 파일(수정 22 + 신규 39). 다음 실행에서 재확인한다.
- Backend 저장 결과 51개 클래스/370개 테스트 통과(50/365 → 51/370): 신규 `SourceTokenConcurrencyTest`(2개, 새 파일) + `SourceConnectionServiceAuditTransactionTest`에 1개 + `GoogleDriveOAuthServiceTest`에 2개 = 순증 5개. `SourceConnectionServiceTokenRemovalTest`의 기존 테스트 1개는 새로 정정된 disconnect() 동작에 맞게 이름/내용을 재작성했다(개수는 그대로). 실제 Google 검증은 여전히 미실행(MVP-03 그대로 유지).
- 이번(3-issue 교정) 세션에서 실제로 코드·테스트 결과가 다시 바뀌었다. 새로 추가한 각 회귀 테스트는 "수정 전 코드로 되돌려 실제로 실패하는지 확인 → 수정 복원 → 다시 통과 확인"의 Revert-Rerun-Restore 절차로 직접 검증했다(추측이 아니다) - `SourceTokenConcurrencyTest`의 Refresh-vs-Disconnect Race, `SourceConnectionServiceAuditTransactionTest`의 Unreadable-Credential Revoke, `GoogleDriveOAuthServiceTest`의 Missing-Refresh-Token 2건, 총 4개 지점 모두 확인.
- 이전 세션(M08 OAuth 최초 구현)에서 두 가지 실제 결함을 발견·수정했었다: (1) Spring Security Token Response Client에 OAuth2 전용 HttpMessageConverter를 등록하지 않으면 실제 Google 응답을 못 읽는 문제, (2) `GoogleTokenService.load()`의 Self-Invocation으로 내부 `store()` 호출이 `@Transactional`을 우회해 Pessimistic Lock이 실패하는 문제.

## 공부용 메모

Token은 Google API용 출입증이고 Master Key는 저장된 출입증을 잠그고 여는 열쇠다. DB에는 잠긴 출입증만 두고 열쇠는 실행 환경에서 별도로 제공한다. Frontend에는 Google Token을 전달하지 않고 연결 성공 여부만 보여준다.
