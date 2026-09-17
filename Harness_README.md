# SDV 하네스 v1 — 적용 대기 패키지

## 결론

**Git 권한 확정:** AI는 현재 branch/HEAD/status/diff와 검증에 필요한 로컬 기준 정보만 읽기 전용으로 조회한다. stage/commit/push/pull/fetch/merge/rebase/checkout/switch/reset/restore/stash/clean, branch/worktree/clone 생성, Git 설정·hook 설치 및 Git 메타데이터 직접 변경은 모두 금지한다. 이를 위한 추가 권한도 요청하지 않는다. 허용된 제품 소스 편집은 별개이며, Git 명령으로 작업 트리를 덮어쓰는 권한은 없다. 소유권 검사나 접근 제한으로 조회가 실패하면 중단하고 보고하며 safe.directory 변경 등으로 우회하지 않는다. 저장소 준비·통합·보호 설정은 사용자가 직접 한다.

요청한 항목별 PowerShell 실행기, 기능별 검증 선택표, 통합 실행기, 15줄 AGENTS/CLAUDE 교체안을 생성했다. 실제 저장소 파일·의존성·Git·OS 권한은 변경하지 않았다.

**스크립트 생성과 강제 통제 배포 완료는 다르다.** 이 패키지는 적용 대기다. 기존 저장소의 지침을 지금 단독 교체하는 것은 권장하지 않는다. 아래 설치·미구현 검증 연결·격리·CI 준비 후 함께 전환한다.

## 파일 배치

- 루트 `AGENTS.md`, `CLAUDE.md`: 동일한 15줄 교체안. 11–13행에 기능별 참조 및 자동 검증 선택 계약을 포함했다.
- `docs/harness/GATE_MATRIX.md`: AI가 작업별로 읽을 계약/테스트와 실제 실행 항목.
- `scripts/harness/verify-change.ps1`: 통합 실행기. 자동 라우팅, 현재 소스 지문, 결과 기록.
- `scripts/harness/00-*.ps1`부터 `09-*.ps1`: 항목별 진단용 실행기.
- `scripts/harness/10-verify-all.ps1`: 통합 검사 단축 진입점.
- `scripts/harness/Harness.psm1`: 경로 검증·Git 읽기·변경 감지·정적 검사·결과 검증 공통 코드.
- `scripts/harness/policy.json`: 필수 테스트 suite와 브라우저 시나리오 목록. 보호 대상 정책이다.
- `scripts/harness/Test-Harness.ps1`: 실제 서비스 없이 검사기 자체를 검증하는 합성 테스트.
- `docs/harness/PHYSICAL_ACCESS_PLAN.md`: 접근 제어 구현·검증 계획. 자동 ACL 변경 스크립트가 아니다.

## 외부 작업 파일 참조 정리 (2026-09-17)

이번 작업 지시(작업 루트 `C:\workspace\secure-document-rag-platform` 밖 참조 정리)에 따라, 이번 대화 기록과 작업 루트 내부 문서·스크립트에서 발견한 외부 경로 참조를 기록한다. 외부 디렉터리는 직접 탐색·열람하지 않았다 - 아래는 대화 기록과 내부 문서 텍스트에서만 수집했다.

1. **`C:\Users\nnpl2\Documents\SDVValidationReports\*.json`(여러 개, 예: `c233cbfbf7a24d6f8dc1e90119d1403d.json`, `b9d0fed4a9a44c0ca553975fe6035103.json`, `vitest-*.json` 등)**
   - 확인된 경로: 예 - 이전 세션들이 `verify-change.ps1 -ReportDirectory`로 실제 생성한 실행 결과다(에이전트가 직접 다시 열어보지는 않았다).
   - 용도/참조 위치: 과거 `verify-change.ps1` 실행 결과 JSON. `VERIFICATION.md`의 실행 기록 항목들과 `.claude-handoff/latest.md`(현재는 매 작업마다 덮어써짐)에서 경로 텍스트로만 참조.
   - 내부 동등 파일: 없음(이번 작업 전에는 전부 외부에만 존재).
   - 반입 필요 여부: 과거 실행 근거일 뿐이며, `VERIFICATION.md`의 서술이 이미 요약을 담고 있다. 사용자가 "과거 검증 이력으로 실제 파일을 보존하고 싶다"고 판단하면 아래 제안 위치로 사용자가 직접 복사하고, 그렇지 않으면 `VERIFICATION.md`의 경로 텍스트를 참조 제거(historical text로만 남김)하는 것으로 충분하다 - 에이전트는 어느 쪽도 자동으로 결정하지 않는다.
   - 제안 내부 위치: `.harness/imported-reports/`.
2. **`C:\SDVValidationReports`(이 문서의 예시 명령이 쓰던 placeholder, 실제 존재 확인 안 됨)**
   - 확인된 경로: 아니오 - 예시 변수 값일 뿐, 실제 그 경로에 파일이 있는지 확인한 적 없다.
   - 용도/참조 위치: 이 문서(`Harness_README.md`)의 "운영자가 실행할 명령" 예시 `$reportDir`.
   - 내부 동등 파일: 있음(대체 완료) - `<RepoRoot>\.harness\reports\`.
   - 반입 필요 여부: 아니오 - 예시 자체를 내부 경로로 교체했다(위 "운영자가 실행할 명령" 절, 완료).
   - 제안 내부 위치: 해당 없음(이미 교체됨).
3. **`C:\Users\nnpl2\Documents\Codex` 아래 생성 산출물**
   - 확인된 경로: **아직 특정되지 않음** - 이번 대화 기록이나 작업 루트 내부의 어떤 문서·스크립트에도 이 경로에 대한 참조가 없다. 에이전트는 이 경로를 열거나 탐색하지 않았다.
   - 용도/참조 위치: 불명 - 사용자가 이번 지시에서 처음 언급했으나 구체적 파일명·내용·용도는 아직 제공되지 않았다.
   - 내부 동등 파일: 판단 불가(내용을 모른다).
   - 반입 필요 여부: **판단 보류 - 사용자 확인 필요.** 이 경로 아래 정확히 어떤 파일이 있고 어떤 용도인지 알려주면, 그때 필요/불필요와 내부 위치를 다시 제안한다.
   - 제안 내부 위치: 보류.
4. **세션 scratchpad(`C:\Users\nnpl2\AppData\Local\Temp\claude\...\scratchpad\`) 및 백그라운드 작업 출력(`...\tasks\*.output`)**
   - 확인된 경로: 예 - 이번 대화 중 에이전트가 직접 만든 일회성 디버그 스크립트(예: `repro.ps1`, `list-test.md`)와 백그라운드 실행 로그.
   - 용도/참조 위치: 순수 디버깅/재현용 - 어떤 내부 문서도 이 경로들을 "참조"하지 않는다(작업 중 생성 즉시 대부분 삭제했다).
   - 내부 동등 파일: 필요 없음 - 최종 산출물이 아니라 임시 작업물이다.
   - 반입 필요 여부: 아니오.
   - 제안 내부 위치: 해당 없음(반입 대상이 아니다).

기존 하네스 패키지(`scripts/harness/**`, `docs/harness/**`, 이 문서)는 오래된 외부 패키지로 덮어쓰지 않았다 - 이번 정리는 참조/경로 교정일 뿐, 파일 내용 자체는 이전 세션들의 교정을 그대로 유지한다.

## 즉시 교체해도 되는가?

방향은 적합하지만 **조건부 승인**이다. 기존 505줄을 삭제하고 15줄만 남기기 전에 다음을 완료한다.

1. 고유한 업무 계약을 CORE_SPEC에 남기고 현재 지침과 충돌하는 과거 상태·명명은 정리한다. 과거 문서는 실행 지시가 아니라 이력으로 보관한다. 현재 진행 중인 보안 교정 작업과 별도 변경으로 관리한다.
2. 이 패키지의 실행기/매트릭스를 검토 후 설치하고 두 지침의 경로가 실제 존재하도록 한다.
3. Prettier의 승인된 고정 버전·lockfile·설정과 실제 브라우저 adapter를 준비한다. 자동 다운로드하지 않는다.
4. ESLint의 기존 경고 3건도 `--max-warnings=0`에서는 실패한다. 정당한 수정 또는 사용자 승인된 제한적 기준선 정책 없이 임의 무시하지 않는다.
5. 정책/실행기/필수 테스트를 AI가 약화시켜 통과할 수 없도록 CI 및 검토 권한을 설정한다.
6. 물리적 접근 제어 검증 후 전환한다. 전환 전까지 원본 지침의 안전 제한은 그대로 유지한다.

AGENTS와 CLAUDE는 하나의 승인된 내용으로만 관리한다. 이 제안에서는 도구별 로딩 차이를 피하려고 동일한 15줄을 양쪽에 배치하고 00 검사가 불일치를 차단한다. 모델에게 두 파일 전체를 반복해서 읽으라고 하지 않는다. 다만 파일 복제 자체보다 단일 기준에서 배포하는 운영 절차가 필요하다.

## 운영자가 실행할 명령

PowerShell 7 전용이다. 실행기/테스트는 Git 상태를 변경하지 않는다. 결과 보고서는 저장소 내부 `.harness/reports/<runId>/`에만 쓴다(2026-09-17부터 - 이전에는 저장소 밖 임의 경로였다) - 이 경로는 `.gitignore`로 제외되어 소스 지문(sourceDigest) 계산에는 들어가지 않으며, `-ReportDirectory` 같은 다른 내부·외부 경로를 지정하는 파라미터는 없다. 기준 SHA는 예시 HEAD를 고정하지 말고 실제 적용 migration을 포함한 사용자 승인 기준을 사용한다.

```powershell
# 아래 변수는 사용자가 실제 값으로 지정한다.
$repoRoot = 'C:\workspace\secure-document-rag-platform'
$baseCommit = '<사용자가 승인한 40자리 기준 커밋 SHA>'

# 1. 검사기 자체 합성 테스트
pwsh -NoProfile -File .\scripts\harness\Test-Harness.ps1

# 2. 변경 파일에 따라 필요한 검사 확인 — 실행하지 않으므로 종료 코드는 2
pwsh -NoProfile -File .\scripts\harness\verify-change.ps1 -RepoRoot $repoRoot -BaseCommit $baseCommit -PlanOnly

# 3. 승인된 격리 테스트 환경에서 전체 필수 검사
pwsh -NoProfile -File .\scripts\harness\verify-change.ps1 -RepoRoot $repoRoot -BaseCommit $baseCommit -IsolatedExecutionApproved
```

실행 결과 JSON은 `.harness/reports/<이번 실행의 runId>/result.json`에 남는다(Gate 07을 포함한 실행이면 같은 디렉터리에 `vitest.json`도 함께 남는다). 과거 실행의 참고 자료(예: 이전 세션이 저장소 밖에 남긴 JSON)는 사용자가 직접 `.harness/imported-reports/`에 복사해 넣을 수 있다 - 하네스는 그 디렉터리를 읽지도 쓰지도 않는다.

현재 호스트의 실제 testbed를 끄거나 재시작할 필요는 없다. 검사 환경을 그것과 분리해야 한다. 기존 보호 파일을 읽는 start-testbed.ps1 같은 런처를 이 하네스가 실행하지 않는다.

## 강제성의 위치

- AI에게 매트릭스와 명령을 알려주는 것: 실행 안내.
- 로컬 스크립트가 실패/미실행에 nonzero 반환하는 것: 검사 결과 통제.
- 독립 CI가 신뢰된 runner로 검증하고 해당 check 없이는 develop 병합을 막는 것: 병합 강제.
- VM/OS/서비스 권한으로 비밀·실제 운영 작업을 막는 것: 실행 차단.

파일을 저장소에 넣는 것만으로 AI가 반드시 실행하는 것은 아니다. 로컬 Hook도 건너뛸 수 있다. GitHub branch rule/required check 설정은 사용자가 수행해야 하며 이번 패키지는 이를 변경하지 않았다.

CI는 PR이 수정한 verify-change.ps1을 그대로 신뢰하지 말고, 운영자가 검토한 고정 버전의 하네스를 별도 읽기 전용 위치에서 실행한다. 기준 SHA도 CI가 정한다. 코드/테스트는 격리 작업공간에서 실행하며, 운영 비밀과 넓은 CI 토큰을 전달하지 않는다. 하네스와 critical test 변경은 별도 승인 대상으로 한다. 서명되지 않은 로컬 JSON을 업로드하는 것만으로 required check를 통과시키지 않는다.

## 검증의 정직한 범위

- 아키텍처 검사는 명시적 import 금지 두 종류만 강제한다. 전체 의존 그래프/리플렉션/동일 패키지 우회까지 보장하려면 별도 아키텍처 테스트를 추가해야 한다.
- authorization/concurrency/non-retention은 기존 실제 테스트 suite를 실행하고 필수 suite 부재를 차단한다. suite 이름이 있다고 단언 내용까지 자동으로 증명되는 것은 아니다.
- browser adapter가 없으면 BLOCKED다. 이 패키지에는 허위 PASS를 내는 대체 구현이 없다.
- 테스트 명령의 원문 출력은 저장하지 않는다. 보고서에는 명령/종료 코드/시각/소스 지문/집계만 포함한다. source hash는 비밀 경로를 제외한다.
- 실행 전후 소스가 바뀌면 실패한다. 악의적인 변경 후 복원 전체를 감지하는 기능은 아니므로 신뢰된 불변 CI snapshot에서 최종 실행해야 한다.
- 프로세스 timeout/cancellation 강제는 격리 runner의 job timeout으로 추가한다. 스크립트 자체가 매달린 JVM을 안전하게 강제 종료한다고 주장하지 않는다.
- 실제 서비스, 브라우저 수락, 물리적 권한 차단은 이번 생성 작업에서 검증하지 않았다.

## 공식 문서 참고

OpenAI Docs를 참고해 지침 로딩과 sandbox/approval을 구분했다. 실제 ACL/VM 프로필은 호스트와 설치된 클라이언트 지원을 확인한 후 운영자가 적용한다.

- [AGENTS.md 로딩](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Sandbox와 승인 정책](https://learn.chatgpt.com/docs/sandboxing)
