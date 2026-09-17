# 기능별 필수 검증과 참조 기준

이 문서는 제안 하네스의 사용 계약이다. 스크립트의 자동 선택이 최소 기준이고, AI는 검사 범위를 추가할 수 있지만 줄일 수 없다. 이 문서 전체와 실행 도구 설명은 상시 프롬프트에 복제하지 않는다.

## 작업 절차

1. 현재 승인 범위와 관련 CORE_SPEC 절만 확인한다.
2. 사용자가 지정한 기준 커밋과 작업 트리의 차이를 `verify-change.ps1 -PlanOnly`로 확인한다. 커밋된 변경, staged/unstaged, Git이 무시하지 않는 신규 파일을 합쳐 판단한다.
3. 아래 검사군의 관련 테스트를 읽고 변경한다. 테스트 수를 맞추려고 불필요한 테스트를 만들거나 검사를 삭제하지 않는다.
4. 개별 실행기는 개발 중 진단용이다. 완료 보고에는 선택 옵션 없는 통합 실행기 결과를 사용한다.
5. 실행기 종료 0은 해당 검사 범위 통과, 1은 위반, 2는 BLOCKED/PLAN_ONLY이다. `mergeReady=true`는 통합 실행의 모든 필수 offline 검사 통과일 뿐 실제 MVP 완료가 아니다.

| 변경 기능                                         | 읽을 명세/기존 테스트                                                                               | 자동 최소 검사                                            |
| ------------------------------------------------- | --------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 모든 작업                                         | 현재 작업 범위, 관련 공개 명세                                                                      | 00 지침, 01 형식, 04 기존 migration 보호, 10 증거         |
| 화면·파일 목록·스타일                             | 해당 컴포넌트 테스트, frontend UI 계약                                                              | 02 타입/lint, 07 UI 계약, 08 실제 브라우저 추가           |
| 인증·공유·권한·API                                | CORE_SPEC §2A, SelectiveSharingE2ETest, IdentityAccessControllerWebTest, MeBootstrapIntegrationTest | 03·05·06·09 및 02·07·08 추가                              |
| 백엔드 일반 기능                                  | 해당 application/domain 계약                                                                        | 03·05·06·09 추가; backend 전체 회귀는 한 실행에서 한 번만 |
| DB·migration                                      | 기존 migration 및 변경 기능 계약, migration 테스트                                                  | 04 및 backend 검사군                                      |
| 검색·RAG·모델·parser                              | 실시간 근거/만료/최종 반환/비보관 계약                                                              | 05·06·09, 관련 AI-service pytest                          |
| 문서 API 계약·하네스·CI·스크립트·기타 미분류 경로 | 관련 명세와 검증 정책                                                                               | 전체 검사로 확대                                          |
| 실행 기록·일반 runbook 문서만 변경                | 해당 작업 기록                                                                                      | 공통 검사; 코드 영향이 있으면 전체 검사 필요              |

파일명 규칙은 의미 분석을 완전히 대신하지 못한다. 예를 들어 문서만 바꿔도 인가 정책을 바꾸는 작업이면 사용자/검토자가 전체 검증을 요구해야 한다. 자동 라우팅의 신규 경로가 누락되지 않도록 하네스 self-test를 추가한다.

## 항목별 실행기

| ID / 파일            | 실제 수행                                                                         | 한계·차단 조건                                                                         |
| -------------------- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| 00-guides.ps1        | 두 지침 15줄 상한·동일성·참조/설치 경로 검사                                      | 실제 모델의 이해도를 증명하지 않음                                                     |
| 01-format.ps1        | git whitespace, 변경 PowerShell 구문, 변경 문서/프런트 파일의 로컬 Prettier check | Prettier 설치 없으면 BLOCKED; 설치/수정 자동 실행 안 함; Java 포매터는 포함하지 않음   |
| 02-types-lint.ps1    | ESLint 경고 0, TypeScript/Vite build                                              | 기존 경고도 실패; 승인 없는 전체 자동 포맷 없음                                        |
| 03-architecture.ps1  | Controller의 repository import, domain/port의 JPA·Web·Google import 검사          | 명시적 import 정적 검사만; 우회 참조·실제 계층 전체는 보장하지 않음                    |
| 04-migrations.ps1    | 신뢰 기준 커밋의 migration 수정/삭제/이름 변경 거부, 숫자 버전 중복·역행 검사     | 기준 커밋은 실제 적용된 migration을 포함해야 함; 운영 DB에 접속하지 않음               |
| 05-authorization.ps1 | backend 전체 회귀와 필수 인가/로그인 suite 실행 여부·실패/skip 검사               | 인가 원칙 전부의 수학적 증명 아님; 필수 테스트 자체의 변경은 별도 리뷰                 |
| 06-concurrency.ps1   | 같은 backend 실행에서 답변/다운로드/캐시/색인/커밋 무효화 필수 suite 확인         | 모든 경합을 증명하지 않음; sleep 대신 제어된 실제 경합 테스트를 유지                   |
| 07-ui-contracts.ps1  | Vitest 전체 JSON 결과에서 실패·skip·todo·0 test 거부                              | jsdom 성공은 실제 viewport 성공이 아님                                                 |
| 08-browser.ps1       | 실제 브라우저 adapter의 실행 ID·소스 지문·필수 시나리오 PASS 확인                 | adapter 없거나 사례 누락/skip이면 BLOCKED; 가짜 receipt로 대체 금지                    |
| 09-nonretention.ps1  | 실제 기존 non-retention/log/audit 테스트와 관련 AI pytest 실행                    | 비밀 파일을 직접 읽거나 전체 개인 로그를 스캔하지 않음; 모든 유출 부재를 보장하지 않음 |
| 10-verify-all.ps1    | 자동 선택된 모든 검사, 소스 시작/종료 지문 비교와 JSON 보고서                     | GitHub required check 등 외부 문지기 없이는 실행을 강제할 수 없음                      |

## 원본 보호와 실행 환경

- Git은 읽기 전용 로컬 상태 조회만 한다. Git 상태·작업 트리·설정·원격을 바꾸는 명령, hook 설치, 권한 요청은 금지한다. 조회 실패 시 Git 설정을 조정하거나 다른 도구로 우회하지 않는다. 명시적으로 허용된 제품 소스 수정과 Git을 이용한 덮어쓰기를 구분한다.

- `RepoRoot`는 사용자/CI가 승인한 루트이며, `BaseCommit`은 사용자가 승인한 전체 40자리 SHA다. AI가 편한 기준점으로 바꾸면 안 된다.
- 비밀/내부 workmd 경로는 Git 경로 선택에서 제외한다. 해당 경로의 안전성은 이 검사기가 아니라 OS/VM/runner가 보장해야 한다.
- 검사 과정에서 소스를 수정하지 않는다. 빌드·테스트의 build/node_modules 캐시 같은 산출물은 생성될 수 있다. Git stage/commit/pull/설정 쓰기는 하지 않는다.
- 실제 런처, Google, testbed DB/Kafka, 실제 모델을 호출하지 않는다. product test 명령 자체는 임의 코드를 실행하므로 실제 자격증명이 없는 격리 환경에서만 사용한다.
- `-IsolatedExecutionApproved`는 운영자가 환경을 확인했다는 명시적 표식이지 sandbox를 생성하거나 네트워크를 차단하는 기능이 아니다.
- backend 의존성은 사전 설치된 캐시만 사용하도록 `--offline`을 지정했다. 부족하면 별도 사용자 준비가 필요하다.
- 검사 원문 stdout/stderr는 일반 보고서에 기록하지 않는다. 출력 내용이 필요한 디버깅은 합성 데이터 기반의 별도 격리 실행으로 제한한다.
- 보고서는 저장소 내부 `.harness/reports/<runId>/`에만 쓴다(`ReportDirectory` 파라미터는 없다 - 임의의 다른 내부·외부 경로는 지정할 수 없다). 이 경로는 `.gitignore`로 제외되어 소스 지문(sourceDigest) 계산에 들어가지 않으며, 매 실행은 자기 `runId`만의 새 하위 디렉터리를 쓰고 기존 결과를 덮어쓰거나 재귀 삭제하지 않는다. 과거 실행의 참고 자료는 `.harness/imported-reports/`에만 두며 하네스가 직접 쓰지 않는다. 비밀 설정의 일치나 환경 완전성을 증명하지 않는다.

## 실제 브라우저 adapter 계약

아직 저장소에 없는 `frontend/package.json`의 `test:e2e:harness` 명령을 별도 구현해야 한다. 승인된 브라우저 테스트 도구로 격리 앱/합성 fixture를 사용하고 실제 서비스는 호출하지 않는다. 의존성 추가는 별도 승인 대상이다.

호출 인자는 `--run-id <UUID> --source-digest <SHA256>`이며 stdout에는 아래 구조의 단일 JSON만 반환한다. 실제 테스트가 완료되지 않았는데 PASS를 작성하면 안 된다. 상세 실패 출력은 stderr를 사용할 수 있으나 통합 보고서에는 저장하지 않는다.

```json
{
  "runId": "실행기가 전달한 값",
  "sourceDigest": "실행기가 전달한 값",
  "cases": [
    { "id": "desktop-navigation", "status": "PASS" },
    { "id": "mobile-focus-and-overlay", "status": "PASS" },
    { "id": "long-file-list-and-sticky-controls", "status": "PASS" },
    { "id": "login-bootstrap", "status": "PASS" },
    { "id": "admin-conflict-reload", "status": "PASS" },
    { "id": "view-only-no-download", "status": "PASS" },
    { "id": "inert-output-no-external-resources", "status": "PASS" }
  ]
}
```

운영 통제자는 runner/policy/필수 테스트 및 adapter 변경을 검토해야 한다. 이 JSON은 암호학적 원격 증명이 아니다.
