# 생성 산출물 검증 기록

- 날짜: 2026-09-17, Asia/Seoul.
- 범위: 별도 outputs 패키지 생성. 실제 SDV 저장소 지침/코드/의존성/Git/OS ACL은 변경하지 않았다.
- 실행기: 설치된 PowerShell 7 runtime의 pwsh.exe.

## 실제 수행

1. `scripts/harness/Test-Harness.ps1` 최종 실행: **43 assertions PASS, 종료 0** (2026-09-17 인벤토리/보호 규칙 일치 교정 이후 재실행; 기존 37건은 그대로 통과, 합성 인벤토리 + 실제 저장소 `Get-SourceDigest` 회귀 6건 추가).
   - Git 변경 명령과 파일 출력 부작용을 실행 전에 거부.
   - 금지 경로/경로 이탈 차단, 기능별 자동 검사 선택.
   - 기존 migration 수정/삭제/중복/역행 거부 및 정상 신규 버전 허용.
   - 좁은 Java import 규칙, 지침 길이/참조/동일성.
   - 필수 backend suite 부재, 빈/skip 결과 거부.
   - 오래되거나 사례가 빠진 browser receipt 거부.
   - native 프로세스의 종료 코드 7 보존, 없는 실행 파일 거부.
   - 패키지 내 모든 PowerShell 파일 구문 분석.
2. Git 정책 강화 전 실제 저장소에서 `verify-change.ps1 -PlanOnly` 실행한 이력. 강화 후 재실행하지 않았다.
   - 사용자 보고/실제 조회 기준 HEAD `6684c6f97c3956997bc364bf812e16a648a543d3` 사용.
   - 00–10 필수 검사 선택, 모두 NOT_RUN, mergeReady=false.
   - 자식 프로세스 실제 종료 코드 **2** 확인.
   - 최종 계획 보고서 runId: `6c08617b02754f9483c325a572deeeb0`.
3. 2026-09-17 `Invoke-Captured`의 stdout/stderr 분리 교정 이후 동일 저장소에서 `verify-change.ps1 -PlanOnly` 재실행.
   - 같은 HEAD `6684c6f97c3956997bc364bf812e16a648a543d3`를 기준 커밋으로 사용, 실제 dirty 작업 트리(수정된 backend/frontend 파일 다수) 대상.
   - 결과: status=PLAN_ONLY, mergeReady=false, requiredGates 00–10 전부 NOT_RUN(reason=plan-only), 종료 코드 **2**.
   - 사용자가 보고한 "requiredGates 비어있는 채 BLOCKED" 증상이 재현되지 않음을 확인. 임시 보고서 파일은 세션 scratchpad에만 생성했으며 확인 후 삭제했다.
4. 2026-09-17 인벤토리/보호 규칙 일치 교정(`Get-SafeRelativePath`를 `Assert-StructurallySafePath`+`Test-ProtectedRelativePath`로 분리) 이후, 같은 HEAD를 기준으로 사용자 승인 하에 `verify-change.ps1 -IsolatedExecutionApproved`(개별 Gate 선택 없는 전체 실행)를 처음으로 preflight 통과까지 실행했다.
   - 결과: Gate 00·02·03·04·05·06·08·09 **PASS**. Gate 01은 이번 세션과 무관한 사전 dirty 파일 22개(0건이 이번 세션 파일) 때문에 예상대로 FAIL. Gate 07/10은 설치된 Vitest 5의 `--reporter=json`이 stdout이 아니라 파일에 JSON을 쓰는 것과 관련된, 이번 승인 범위 밖의 새 harness 결함으로 FAIL(`docs/plan/SDV_MVP_DEFERRED.md`에 원인·최소 수정안 기록, 이번에는 수정하지 않음).
   - `mergeReady=false`(FAIL). 최종 JSON 보고서는 `C:\Users\nnpl2\Documents\SDVValidationReports\c233cbfbf7a24d6f8dc1e90119d1403d.json`.
5. 2026-09-17 위 Gate 07/10 결함 교정 + 서식 실패 22개 파일 정리(사용자 승인) 이후, 같은 HEAD로 `verify-change.ps1 -IsolatedExecutionApproved` 재실행.
   - 결과: Gate **00·02·03·04·05·06·07·08·09·10 전부 PASS**(`sourceDigestBefore == sourceDigestAfter`). Gate 01만 `docs/spec/SDV_v3.2_CORE_SPEC.md` 한 파일 때문에 FAIL(Prettier의 markdown 순서 목록 정규화가 문서 원문 번호를 바꾸려 해서, 사용자 결정에 따라 원문 번호를 그대로 복원했고 그 결과 이 파일만 Prettier와 근본적으로 불일치).
   - 전체 `status=FAIL`, `mergeReady=false`. 최종 JSON 보고서는 `C:\Users\nnpl2\Documents\SDVValidationReports\b9d0fed4a9a44c0ca553975fe6035103.json`.
   - AGENTS.md/CLAUDE.md는 SHA256 해시로 재포맷 전/후 완전히 동일함을 확인(15줄·동일성 계약 불변).
6. 2026-09-17 외부 작업 파일 참조 정리(보고서 경로를 저장소 내부 `.harness/reports/<runId>/`로 전환, `docs/spec/SDV_v3.2_CORE_SPEC.md`에 `<!-- prettier-ignore -->` 적용, 우연히 함께 드러난 `scripts/harness/policy.json` 서식만 정리) 이후, 같은 HEAD로 `verify-change.ps1 -IsolatedExecutionApproved`(파라미터에서 `-ReportDirectory` 제거됨) 재실행.
   - 결과: **Gate 00·01·02·03·04·05·06·07·08·09·10 전부 PASS. `status=PASS`, `mergeReady=true`**(이 하네스가 처음으로 도달한 완전한 PASS).
   - `sourceDigestBefore == sourceDigestAfter`. 최종 JSON 보고서는 저장소 내부 `.harness/reports/9b53f21987364ddebd52bf6e76cfb6c1/result.json`(같은 디렉터리에 `vitest.json`도 있음) - `.gitignore`로 제외되어 있으며 이번에는 삭제하지 않고 그대로 보존했다.

## 개발 중 발견하고 고친 문제

- PATH에 여러 node.exe가 있을 때 Get-Command 결과를 하나의 실행 파일로 해석하지 못하던 문제를 첫 Application 선택으로 수정했다.
- PowerShell module 내 지역 LASTEXITCODE가 native 종료 코드를 가리는 문제를 수정하고, 종료 코드 보존 및 missing executable 회귀를 확인했다.

## 수행하지 않은 항목

- 기존 SDV frontend/backend/AI 제품 테스트, production build, 실제 브라우저 테스트.
- 실제 서비스 시작/중단/모델 호출, DB migration 적용, 계정/등급 변경.
- CI required check 설치, 운영 파일 ACL/VM/network 정책 적용.
- 패키지의 원본 저장소 배포 및 AGENTS.md/CLAUDE.md 교체.

43개 통과(기존 37 + 인벤토리/보호 규칙 회귀 6)는 하네스 합성 테스트 결과다. 제품 테스트 통과, 전체 하네스 배포 완료 또는 물리적 접근 차단 검증으로 해석하지 않는다. 위 6번 결과로 전체 `verify-change.ps1`이 `status=PASS`/`mergeReady=true`에 실제로 도달했다는 것은 확인됐지만, 이는 오프라인 검사 통과일 뿐이며 실제 라이브 Google/Keycloak/Kafka/모델 수락, 물리적 접근 차단, CI required check 강제는 이 실행이 증명하지 않는다(아래 "수행하지 않은 항목" 참고).

## Git 읽기 전용 정책 반영

- 기존 지침·검사 스크립트·접근 제어 문서만 수정했다. 새 문서는 만들지 않았다.
- Git 변경 및 관련 권한 요청을 금지하고, safe.directory 및 기타 일회성 Git 설정 재정의도 제거했다.
- 읽기 명령에 optional lock 비활성화를 적용하고 diff 외부 실행과 textconv를 차단했다. 소유권 오류는 우회하지 않는다.
- 스크립트 내부 허용 목록은 물리적 차단을 대신하지 않는다. 원본 저장소와 OS 접근 제어는 변경하지 않았다.

원본 `.claude-handoff/latest.md`는 관제 쓰기 권한 종료 및 이번 지침 교체 판단 범위를 존중하여 수정하지 않았다. 이 파일과 README가 이번 생성 작업의 별도 handoff다.
