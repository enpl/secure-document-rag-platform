# AGENTS.md

이 문서는 Secure Document Vault (SDV) 프로젝트에서 작업할 때 지켜야 할 규칙을 정의한다.

## 프로젝트

- 프로젝트명: Secure Document Vault (SDV)
- 목표: 기업 문서를 Source 권한과 SDV 정책에 따라 안전하게 검색하고 AI에 연결하는 Secure RAG Gateway
- 현재 단계: Core MVP 개발
- 최신 v3.2 상세명세를 최우선 Source of Truth로 사용
- README와 상세명세가 충돌하면 v3.2 상세명세를 우선한다.

## 기술 스택

- Java 21
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Flyway
- Keycloak
- Kafka
- Python FastAPI
- React
- Ollama
- Docker Compose
- Kubernetes는 Core 이후 단계
- AWS EKS는 Cloud Portfolio 단계

## Backend Architecture

- Package-by-feature + Ports/Adapters 구조 유지
- Controller는 HTTP 요청/응답, 입력 검증, 인증 컨텍스트 처리까지만 담당
- Controller에서 Repository를 직접 호출하지 않는다.
- Application Service가 Use Case와 Transaction Boundary를 담당한다.
- Domain/Port는 JPA Entity, HTTP DTO, Google SDK 같은 외부 기술에 의존하지 않는다.
- Adapter가 DB, Google Drive, Ollama, Kafka 등의 외부 기술을 구현한다.
- JPA Entity를 REST API 응답으로 직접 반환하지 않는다.
- API DTO와 Persistence Entity를 분리한다.
- Interface + Composition을 우선한다.
- Abstract Class는 공통 Workflow invariant가 명확할 때만 사용한다.

## Database

- Hibernate ddl-auto=create/update를 사용하지 않는다.
- DB Schema 변경은 Flyway Migration으로만 관리한다.
- 이미 적용된 Migration 파일은 수정하지 않는다.
- 새 변경은 V002, V003처럼 새로운 Migration으로 추가한다.
- 현재 V001__baseline.sql은 Core relational schema이다.
- document_chunks와 pgvector는 V002 범위이다.

## Security

- Source에서 DENY된 권한을 SDV가 ALLOW로 확장하지 않는다.
- Permission Unknown / Stale / Connector Error는 Fail Closed 한다.
- Retrieval 전에 Effective Permission을 계산한다.
- 권한 없는 Document/Chunk는 Retrieval과 LLM 입력에 절대 포함하지 않는다.
- Token, API Key, Secret, 문서 원문, Prompt 원문을 일반 로그나 Kafka Event에 기록하지 않는다.
- External LLM은 기본 OFF이다.
- Local LLM(Ollama)을 기본 Provider로 사용한다.

## Kafka / Event

- Kafka는 문서/권한 변경과 비동기 Indexing 같은 실제 비동기 흐름에만 사용한다.
- Transactional Outbox 패턴을 사용한다.
- Retry / DLQ / Idempotency를 고려한다.
- 단순 포트폴리오 장식 목적으로 Kafka를 추가하지 않는다.

## Audit

- Source → Policy → Retrieval → LLM → Citation 흐름을 traceId와 reasonCode로 추적 가능해야 한다.
- Audit에는 문서 원문, Token, Secret을 저장하지 않는다.

## AI Agent 작업 규칙

- 작업 전에 현재 Repository 파일을 먼저 조사한다.
- 존재하지 않는 구조나 파일을 추측하지 않는다.
- 구현 전에 Plan과 변경 예정 파일 목록을 먼저 제시한다.
- 사용자의 승인을 받은 범위만 수정한다.
- 한 번에 최소 범위만 변경한다.
- Architecture 또는 Dependency 변경은 반드시 먼저 설명하고 승인을 받는다.
- 요청하지 않은 Refactoring을 하지 않는다.
- 요청하지 않은 Dependency를 추가하지 않는다.
- 자동으로 git add, commit, push 하지 않는다.
- git reset --hard, force push, history rewrite, branch 강제 삭제를 하지 않는다.
- destructive command가 필요하면 실행 전에 이유를 설명하고 승인을 요청한다.
- 작업 완료 후 변경 파일, 변경 이유, 테스트 결과, 남은 작업을 보고한다.

## Learning Rule

- 새로운 JPA, Flyway, Spring Security, Kafka, Kubernetes 패턴을 도입할 때는 왜 필요한지 간단히 설명한다.
- 사용자가 이해해야 하는 핵심 개념과 AI에게 위임 가능한 반복 구현을 구분해서 설명한다.
