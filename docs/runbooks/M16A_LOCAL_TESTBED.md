# M16A 로컬 테스트베드 Runbook

이 문서는 SDV를 **실제 Keycloak 로그인 + 사용자 본인 Google 계정**으로 반복
검증할 수 있는, 기존 개발 환경과 완전히 분리된 로컬 테스트베드 사용법을
설명한다. 추가 비용은 전혀 발생하지 않는다 - 모든 것이 로컬 Docker/기존
Gradle·npm 도구로만 동작한다.

> **M10B 정합화 노트(2026-09-15, Backend Only)** - 아래 ADMIN A/B 절차는 여전히
> 정확하다(기존 ADMIN 전용 "연결 관리" Frontend 화면과 `/api/admin/sources/**`
> 목록/생성/삭제 API는 이번 작업에서 전혀 바꾸지 않았다). 다만 백엔드 인가
> 표면 자체는 넓어졌다 - `GET /api/admin/sources/google/authorize`(재인증
> 시작)는 이제 ADMIN Role 없이도 일반 인증된 USER가 자신이 소유한 Source에 대해
> 호출할 수 있고(`SecurityConfig`의 정확히 이 경로/Method 하나에 대한 예외),
> 같은 소유자·검증된 동일 Google 계정이면 이미 Disconnect된(DISABLED) Source도
> 이 같은 Endpoint로 재연결할 수 있다(`docs/spec/SDV_M08_TOKEN_CONTRACT.md`
> M10B 교정 참고). 일반 USER 본인이 파일을 선택 공유하는 화면(M16C)은 이번
> 작업에서 아직 만들지 않았다 - 그런 화면이 이미 존재한다고 가정하지 않는다.
> 아래 ADMIN A/B 수동 절차는 여전히 Frontend가 실제로 제공하는 유일한 UI
> 경로만 사용한다.



## 0. 이 테스트베드가 하지 않는 것

- 기존 `infra/docker-compose.dev.yml`(포트 5432/8080/8180/5173) 컨테이너/볼륨을
  전혀 건드리지 않는다 - 완전히 별도의 Compose 프로젝트(`sdv-testbed`)다.
- Google을 Keycloak의 Identity Provider(소셜 로그인)로 쓰지 않는다 - Keycloak
  애플리케이션 로그인과 Google Drive OAuth 연결은 여전히 서로 다른 별개의
  절차다.
- 유료 리소스를 만들지 않는다 - Google Cloud Billing 계정 연결, 카드 등록,
  무료 체험 등록, Quota 증설, 유료 보안 점검/라이선스 설치를 요구하지 않는다.
- Drive를 자동으로 크롤링/폴링하지 않는다 - 사용자가 미리 지정한 파일 1개만,
  사용자가 직접 누를 때만 읽는다.

## 1. 사전 준비물

- Docker Desktop(엔진이 실제로 켜져 있어야 한다 - 이 스크립트는 대신 켜주지
  않는다)
- Java 21, Node.js(이 저장소가 요구하는 버전)
- PowerShell 7+ (`pwsh`)
- 포트 15173/18080/18180/15432가 비어 있어야 한다(다른 프로그램이 이미 쓰고
  있으면 스크립트가 명확한 오류로 멈춘다 - 기존 사용 중인 프로세스를 강제
  종료하지 않는다)

## 2. 시작 / 상태 확인 / 종료

```powershell
# 시작 (Postgres+Keycloak Docker 기동 -> 계정 생성 -> Backend/Frontend 기동까지 한 번에)
scripts\testbed\start-testbed.ps1

# 상태 확인 (실제 Endpoint를 두드려 확인한다 - 프로세스가 떠 있다고 곧바로 READY로 보고하지 않는다)
scripts\testbed\status-testbed.ps1

# 종료 (이 테스트베드가 시작한 프로세스/Compose만 정지 - 데이터/계정은 보존된다)
scripts\testbed\stop-testbed.ps1
```

시작이 끝나면 다음이 출력된다:

- Frontend 주소: `http://localhost:15173`
- Backend 주소: `http://localhost:18080`
- Keycloak 주소: `http://localhost:18180` (realm `sdv-testbed`)
- 계정 자격증명 파일 위치(아래 3절 참고)
- Google 설정 파일 위치(LIVE GOOGLE 단계, 아래 5절 참고)
- Backend/Frontend 로그 파일 위치(`infra/testbed/.logs/backend.log`,
  `infra/testbed/.logs/frontend.log`) - 창을 숨긴 채로 실행되므로, 문제가
  있으면 이 파일을 열어 확인한다(`Get-Content -Tail 50
  infra\testbed\.logs\backend.log`). 비밀번호/Google Client Secret/암호화
  Key 등은 이 로그에 절대 기록되지 않는다.
- 시작 시점에 이미 검증된(추측이 아닌) 각 서비스가 실제로 `127.0.0.1`에만
  바인딩됐는지 여부 - `0.0.0.0`/`::`에 바인딩됐으면 READY로 보고하지 않고
  오류로 표시한다.

## 3. 계정 자격증명 확인 방법 (비공개)

계정 이름/비밀번호는 **절대 이 문서나 채팅에 적히지 않는다.** 최초 시작 시
1회만 생성되어 다음 파일에 저장된다(Git에 포함되지 않음):

```
infra/testbed/secrets/testbed-accounts.local.txt
```

이 파일을 직접 열어 `sdv-user` / `sdv-admin-a` / `sdv-admin-b` 각각의
비밀번호를 확인한다. 파일이 이미 있으면 재시작해도 값이 바뀌지 않는다.

Keycloak 자체의 서버 관리자(Admin Console, `http://localhost:18180`의 별도
로그인)는 `admin` / `admin`이다 - 이는 SDV 애플리케이션 ADMIN 역할과 무관한,
Keycloak 자신의 관리자 계정이다.

## 3.5 알려진 이슈와 정정(2026-09-14): 로그인은 됐는데 곧바로 인증 오류

**실제로 관찰된 증상**: 실제 브라우저로 로그인해 얻은 Access Token이 issuer/
audience/ADMIN Role은 전부 정확했지만 `sub`(Subject) Claim이 아예 없었다 -
아직 만료되지 않은 시점이었는데도 Backend(`SecurityConfig`)가 이를 올바르게
거부했다(`sub` 없는 Token을 통과시키는 것이 오히려 보안 결함이므로, 이 거부
자체는 정상 동작이다).

**원인**: `infra/keycloak/realm-export.json`/`infra/testbed/realm-export.testbed.json`의
`sdv-frontend` Client가 Keycloak 기본 제공 `basic` Default Client Scope를
요청하지 않고 있었다 - 이 `basic` Scope 안에 `sub` Claim을 실제로 채워 넣는
Mapper가 들어있다. 이번에 두 파일 모두 `defaultClientScopes`에 `"basic"`을
추가했다(다른 Scope/Redirect URI/Role/Mapper는 그대로다).

**중요 - 이미 만든 Realm에는 자동으로 반영되지 않는다**: Keycloak의
`--import-realm`은 **이미 존재하는 Realm을 덮어쓰지 않는다**(Fresh, 즉 빈
Data Volume에서만 실제로 Import된다 - 이 문서 전체가 이미 이 전제로
작성돼 있다). 즉 이 JSON 파일을 고친 것만으로는, 이미 한 번 만들어진
`sdv-testbed`(또는 기존 `sdv`) Realm이 자동으로 갱신되지 않는다. 이미 만든
Realm에서 실제로 고치려면 **직접, 수동으로** 아래 중 하나를 한다:

- Keycloak Admin Console(`http://localhost:18180`, `admin`/`admin`) ->
  해당 Realm -> Clients -> `sdv-frontend` -> Client scopes 탭에서 `basic`을
  Default로 추가한다(권장 - 기존 계정/데이터를 전혀 건드리지 않는다).
- 또는 완전히 새로 시작하고 싶다면 이 문서 7절의 "전체 초기화"를 수행한 뒤
  `start-testbed.ps1`을 다시 실행한다(계정 비밀번호가 새로 생성된다).

이 Runbook/코드 교정 자체는 **다음에 새로 Import되는 Realm**부터 올바르게
동작한다는 뜻이다 - 이미 떠 있는 Realm은 위 수동 절차 전까지는 여전히 같은
증상을 보일 수 있다.

## 4. BASELINE 체크리스트 (Google 계정 불필요)

아래 표를 인쇄하듯 채워가며 진행한다. `http://localhost:15173`에서 시작한다.

### USER (sdv-user)

| 단계 | 조작 | 기대 결과 | PASS/FAIL |
|---|---|---|---|
| 1 | "로그인" 클릭 -> Keycloak 로그인 화면에서 sdv-user/비밀번호 입력 | 로그인 성공, Home으로 복귀 | |
| 2 | Home 화면 확인 | "업무 문서에 질문해 보세요." + 준비 중 안내, 가짜 답변 없음 | |
| 3 | 왼쪽 메뉴 확인 | "연결 관리" 메뉴 자체가 보이지 않음 | |
| 4 | 주소창에 직접 `http://localhost:15173/admin/sources` 입력 | 화면에는 "관리자만 사용할 수 있습니다" 안내만 보임(관리 화면 아님) | |
| 5 | (선택) 로그아웃 | Home/로그인 화면으로 복귀, 다시 로그인 요구 | |

### ADMIN A (sdv-admin-a)

| 단계 | 조작 | 기대 결과 | PASS/FAIL |
|---|---|---|---|
| 1 | 로그인 -> "연결 관리" 클릭 | Source 목록 화면(처음엔 비어 있음) | |
| 2 | Source 이름 입력 후 "Source 등록" | 실제로 저장된 이름이 목록에 나타남, 상태 "Google 계정 연결 필요" | |
| 3 | 새로고침(F5) | 방금 등록한 Source가 그대로 남아 있음(재조회 확인) | |
| 4 | Google 설정 없이 "Google 연결" 클릭 | 오류 배너로 안내(가짜 동의 화면/가짜 성공 없음) - 서버 측 `OAUTH_UNAVAILABLE` | |
| 5 | "연결 해제" -> "연결 해제 확인" | 상태가 "연결 해제됨"으로 바뀌고, "Google 연결" 버튼 대신 "다시 연결할 수 없습니다" 안내로 바뀜 | |

### ADMIN B (sdv-admin-b) - 소유권 격리 확인

| 단계 | 조작 | 기대 결과 | PASS/FAIL |
|---|---|---|---|
| 1 | 로그인 -> "연결 관리" | ADMIN A가 만든 Source가 전혀 보이지 않음 | |
| 2 | (개발자 도구 등으로) ADMIN A Source의 실제 ID를 알아내 그 ID로 연결 해제/조회를 직접 시도 | 서버가 거부(404류) - A의 Source 이름/상태 등 어떤 정보도 새어나오지 않음 | |

> 참고: 4번은 "다른 Keycloak 계정끼리는 서로의 Source를 못 본다"는 소유권
> 격리 확인이다. **Google 계정 권한 확인과는 다른 질문이다** - 아래 5절
> 마지막 항목 참고.

## 5. LIVE GOOGLE 단계 - 사용자 본인 Google 계정 설정

이 단계는 전적으로 선택 사항이며, 사용자 본인 책임 하에 본인 Google
계정으로 진행한다. **무료 범위 내에서만 진행하고, 결제 계정 연결/유료 등급
전환을 요구받으면 그 단계에서 멈추고 이 사실을 알려달라.**

1. https://console.cloud.google.com 에서 **이 테스트베드 전용의 새 프로젝트**를
   만든다(청구 계정을 연결하지 않는다).
2. "API 및 서비스 -> 라이브러리"에서 **Google Drive API**를 사용 설정한다.
3. "API 및 서비스 -> OAuth 동의 화면"에서 User Type을 **외부(External)**로,
   게시 상태를 **테스트(Testing)**로 둔다(**프로덕션으로 전환하지 않는다**).
   **정정**: Testing 상태 자체가 "무료 범위를 지키는 장치"는 아니다 -
   비용은 Google Cloud 프로젝트에 결제 계정을 연결하고 유료 API/Quota를
   실제로 쓸 때만 발생하며, 이 절차는 애초에 결제 계정을 연결하지 않는다.
   Testing 상태를 유지하는 실제 이유는 비용이 아니라 **심사(App
   Verification)**다 - `drive.readonly`는 Google이 "민감한 범위"로
   분류해, Production으로 게시하면 Google의 앱 심사를 통과해야 계속 쓸 수
   있다. Testing 상태는 그 심사 없이 등록한 테스트 사용자만으로 바로 쓸 수
   있는 대신, 아래("Testing 모드의 알려진 제약")의 7일 Refresh Token
   만료를 감수한다.
4. "테스트 사용자"에 **본인의 Google 계정 이메일**을 추가한다(추가하지
   않으면 동의 화면에서 거부된다).
5. 범위(Scope)는 기존에 이미 정해진 `.../auth/drive.readonly` 하나만
   사용한다(새 Scope를 추가하지 않는다).
6. "사용자 인증 정보 -> 사용자 인증 정보 만들기 -> OAuth 클라이언트 ID" ->
   애플리케이션 유형 **웹 애플리케이션**을 선택하고, 승인된 리디렉션 URI에
   정확히 다음을 등록한다(포트를 바꿨다면 실제 Backend 포트로 교체):

   ```
   http://localhost:18080/api/admin/sources/google/callback
   ```

7. 발급된 **클라이언트 ID/보안 비밀(Client Secret)**을 다음 파일에 붙여
   넣는다(절대 채팅/커밋에 붙여넣지 않는다):

   ```
   infra/testbed/secrets/google-oauth.local.env
   ```

8. 본인 Drive에 **몇 KB 수준의 아주 작은 순수 텍스트(.txt) 테스트 파일
   1개**를 업로드하고, 그 파일을 열었을 때 브라우저 주소창의 파일 ID
   (`/d/`와 `/view` 사이 문자열)를 복사해 같은 파일의
   `SDV_TESTBED_DIAGNOSTICS_ALLOWED_FILE_ID` 값으로 붙여 넣는다.
   **정확히 알아둘 것**: 이 진단의 1MiB 상한은 "다운로드 자체를 막는"
   사전 상한이 아니다 - 기존 Google Drive Connector가 이미 갖고 있는 더
   큰(25MB) 상한으로 파일을 실제로 다 받아온 **뒤에** 그 결과 크기를
   검사한다. 그러니 "1MiB에 가까운 파일도 괜찮겠지"가 아니라, 처음부터
   몇 KB짜리 파일을 쓰는 것이 안전하고 빠르다.
9. `scripts\testbed\start-testbed.ps1`을 다시 실행한다(이미 떠 있다면 먼저
   `stop-testbed.ps1`로 종료 후 재시작 - 새 환경변수를 Backend 프로세스에
   반영하기 위함).

### LIVE GOOGLE 수락 체크리스트 (ADMIN A, 본인 계정)

| 단계 | 조작 | 기대 결과 | PASS/FAIL |
|---|---|---|---|
| 1 | ADMIN A로 로그인 -> 새 Source 등록 | 목록에 나타남, "Google 계정 연결 필요" | |
| 2 | "Google 연결" 클릭 | 실제 Google 동의 화면으로 이동 | |
| 3 | 본인 Google 계정으로 동의 | `http://localhost:15173/admin/sources`로 복귀, 성공 배너 표시 | |
| 4 | 배너 확인 | 배너는 "처리했다"는 표시일 뿐 - 곧이어 목록이 다시 조회되어 "Google 계정 연결됨"으로 바뀌는지 확인 | |
| 5 | 화면 하단 "테스트베드 진단: 파일 읽기 확인 (Local Testbed 전용)" 펼치기 -> 방금 만든 Source 선택 + 8번에서 지정한 File ID 입력 -> "읽기 확인" | `success: true`, `outcome: VERIFIED`, 실제 읽은 바이트 수 표시(파일 내용 자체는 어디에도 보이지 않음) | |
| 6 | 로그아웃 후 다시 로그인 | 여전히 "Google 계정 연결됨" 상태 유지 | |
| 7 | 같은 Source에서 "연결 해제" -> 확인 | 상태가 "연결 해제됨"으로 바뀜 | |
| 8 | 진단 패널의 "대상 Source" 목록에서 방금 연결 해제한("- 연결 해제됨" 표시가 붙은) 바로 그 Source를 **일부러** 선택하고 같은 File ID로 "읽기 확인" | 거부됨(`outcome: SOURCE_NOT_ACTIVE`) - Google을 호출하지 않고 즉시 거부. 이 목록은 연결 해제된 Source도 일부러 계속 보여준다(이 검증을 실제로 해볼 수 있게 하려는 의도) | |
| 9 | (선택) 동의 화면에서 취소를 눌러 재시도 | 실패 배너로 안내, 재시도 가능, 기존 다른 연결에는 영향 없음 | |

패널이 처음부터 보이지 않으면(회색 안내문만 보이면) `scripts\testbed\start-testbed.ps1`으로 띄운 Frontend가 맞는지 확인한다 - 이 패널은 `VITE_TESTBED_MODE=true`로 실행된 Frontend에서만 보이도록 의도적으로 숨겨져 있다(일반 `npm run dev`에서는 보이지 않는다). "확인 중..."에서 멈춰 있거나 회색 비활성 안내가 뜨면 Backend가 `testbed` Profile + 진단 Flag로 켜져 있는지 확인한다(상태 확인용 별도 Endpoint 자체가 없으면 그렇게 표시된다) - 이는 실패가 아니라 정직한 미제공 표시다. 반대로 파일 선택/입력 후 "읽기 확인"을 눌렀는데 오류가 뜨면, 그것은 이제 실제 오류다(패널이 이미 위 확인을 통과해 열려 있었기 때문).

**진행 상황 기록(사용자 직접 확인, Agent가 대신 실행/검증한 것이 아님)**: 사용자가
직접 위 1~4단계에 해당하는 흐름을 실제로 진행해 실제 Google 동의 화면까지
도달했고, 등록된 Source가 "Google 계정 연결됨" 상태로 표시된 화면을
스크린샷으로 확인했다. **이것이 증명하는 것**: 실제 Keycloak 로그인 +
Google OAuth 연결 자체는 최소 한 번 성공했다. **이것이 증명하지 않는 것**
(5~9단계, 여전히 미검증/PENDING): 진단 패널을 통한 실제 파일 읽기 성공,
연결 해제/실제 Google Revoke, 재로그인 후 상태 유지, ADMIN B 교차 소유권
거부. 이 스크린샷 하나만으로 이 항목들이 통과했다고 넘겨짚지 않는다 - 각
행은 실제로 눌러보고 별도로 확인해야 한다.

**중요한 구분**: 4절의 ADMIN B 확인은 "**SDV 소유권**"만 증명한다(다른 SDV
계정끼리 서로의 Source metadata를 못 본다는 것). 이는 "**두 개의 서로 다른
Google 계정 사이의 권한 분리**"를 증명하지 않는다 - 그것을 확인하려면
ADMIN B 본인의 또 다른(사용자 본인 소유의) Google 계정으로 별도 Source를
만들어 같은 절차를 반복해야 한다(이 Runbook은 계정 1개로도 핵심 흐름을
검증할 수 있도록 설계됐다 - 두 번째 Google 계정 확인은 선택 사항이다).

`drive.readonly` 범위 자체는 **Google 계정 전체의 읽기 권한**이다 - "이
테스트 파일 폴더만" 같은 제한된 권한이 아니다. 이 진단이 딱 1개 파일만
읽도록 제한하는 것은 **SDV 애플리케이션 자신의 허용 목록(Allowlist)**이지,
Google이 부여한 권한 범위 자체가 아니다. 즉 Google에 동의하는 순간 이
Client는 기술적으로 Drive 전체를 읽을 수 있는 권한을 받지만, SDV 코드가
스스로 "허용 목록에 있는 파일 1개만" 쓰겠다고 약속하는 것뿐이다.

### Testing 모드의 알려진 제약

OAuth 동의 화면이 "테스트(Testing)" 상태인 동안, 이 Scope로 발급된
Refresh Token은 보통 **7일 후 만료**된다(Google 공식 정책, Publishing
status 참고). 만료되면 "Google 재연결" 버튼으로 같은 화면에서 다시 동의하면
된다 - 이 만료를 피하려고 앱을 "프로덕션"으로 전환하지 않는다(그 순간
Google의 심사/신뢰도 요구사항이 달라진다 - 이 테스트베드의 목적과 맞지
않는다).

## 6. 문제 해결 (Troubleshooting)

| 증상 | 원인/조치 |
|---|---|
| 로그인은 되는데 화면이 곧바로 "인증을 확인하지 못했습니다"로 튕김(로그인 화면으로 되돌아감) | 3.5절 참고 - `sdv-frontend`에 `basic` Default Client Scope가 없어 발급된 Token에 `sub`가 없을 수 있다(Backend가 이를 올바르게 거부한 것). 이미 만든 Realm은 이 저장소의 JSON 파일을 고쳐도 자동으로 반영되지 않는다 - 3.5절의 수동 절차(Admin Console에서 직접 추가, 또는 전체 초기화 후 재시작)를 따른다 |
| Keycloak 로그인 후 리디렉션 오류 | `sdv-frontend` Client의 Redirect URI가 정확히 `http://localhost:15173/*`인지 Keycloak Admin Console(`http://localhost:18180`, admin/admin)에서 확인 |
| 로그아웃 후 오류 페이지 | 같은 Client의 Web origins/`post.logout.redirect.uris`가 `+`(Redirect URI와 동일)로 되어 있는지 확인 |
| Google 쪽에서 "액세스 차단됨"/"확인되지 않은 앱" | 동의 화면이 Testing 상태에서 본인 이메일이 "테스트 사용자"에 없을 가능성 - 5절 4번 확인 |
| `redirect_uri_mismatch` | Google Cloud OAuth Client의 승인된 리디렉션 URI가 Backend가 실제로 보내는 값과 정확히 한 글자도 다르지 않아야 한다(포트 포함) |
| "Google 연결" 클릭 시 즉시 오류(OAuth 사용 불가) | `infra/testbed/secrets/google-oauth.local.env`가 비어 있음 - 5절 7번 확인 후 재시작 |
| 이전에 저장한 Google 연결이 어느 날 갑자기 안 됨 | Testing 모드 7일 Refresh Token 만료 가능성 - "Google 재연결"로 다시 동의 |
| 진단에서 "허용 목록에 없다"는 오류 | `google-oauth.local.env`의 File ID가 실제로 시도한 파일과 다름 - 정확히 일치해야 한다(1개만 허용) |
| 진단에서 "이 Source 접근권한이 없다"는 오류(버전 변경 등) | 파일을 방금 수정했다면 정상 동작이다(Version 재검증) - 다시 시도 |
| 포트 충돌로 시작 실패 | 다른 프로그램이 15173/18080/18180/15432를 쓰고 있다 - 그 프로그램을 끄거나, 이 테스트베드를 종료할 때까지 기다린다(스크립트가 대신 끄지 않는다) |
| `start-testbed.ps1`을 다시 실행했더니 아무것도 안 하고 바로 끝남 | 이미 이 스크립트가 띄운(추적 중인) Backend/Frontend가 살아있다는 뜻이다 - 중복 실행을 막기 위한 정상 동작이다. `status-testbed.ps1`로 실제 상태를 확인하거나, 재시작하려면 먼저 `stop-testbed.ps1`을 실행한다 |
| `frontend/node_modules is missing` 오류로 시작 실패 | `frontend/`에서 `npm install`을 먼저 한 번 실행해야 한다(이 스크립트는 의존성을 대신 설치하지 않는다) |
| Backend가 응답하지 않음(READY 미표시) | `Get-Content -Tail 80 infra\testbed\.logs\backend.log`로 실제 원인을 확인한다(창이 숨겨져 있을 뿐, 로그는 항상 파일에 남는다) |

**절대 하지 말아야 할 것**: 위 오류들을 "우회"하려고 경고를 무시하거나,
Drive 파일/폴더를 공개로 바꾸거나, 인증을 약화시켜 PASS를 만들지 않는다 -
실패는 실패로 기록하고 원인을 고친다.

## 7. 전체 초기화 (파괴적 - 수동 전용, 자동 실행 안 됨)

`stop-testbed.ps1`은 데이터를 지우지 않는다. 완전히 처음부터 다시 만들고
싶을 때만, 아래를 **직접, 순서대로, 신중하게** 실행한다(이 저장소의 어떤
스크립트도 이를 자동으로 수행하지 않는다):

```powershell
scripts\testbed\stop-testbed.ps1
docker compose -p sdv-testbed -f infra\testbed\docker-compose.testbed.yml down -v
Remove-Item -Recurse -Force infra\testbed\secrets
Remove-Item -Recurse -Force infra\testbed\.pids
Remove-Item -Recurse -Force infra\testbed\.logs
```

이후 `start-testbed.ps1`을 다시 실행하면 계정 비밀번호/암호화 키가 전부
새로 생성된다.
