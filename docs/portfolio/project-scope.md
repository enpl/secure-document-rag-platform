# Secure Document RAG Platform

## 프로젝트 목적

기업 내부의 기술 문서를 등록하고,
사용자가 접근 권한을 가진 문서를 대상으로 검색 및 AI 질의를 할 수 있는 서비스를 구현한다.

## 주요 사용자

- 일반 사용자
- 관리자

## MVP

MVP(Minimum Viable Product, 최소 기능 제품)에서는 아래 기능을 구현한다.

1. 문서 등록
2. 문서 조회
3. AI 질문
4. RAG 기반 관련 문서 검색
5. 답변 생성
6. 답변 출처 표시

## 이후 확장 기능

- Keycloak 기반 로그인
- 문서별 접근 권한
- JPA 기반 데이터 관리
- QueryDSL 기반 동적 검색
- React/TypeScript 사용자 화면
- Redis 기반 캐시 또는 Rate Limiting
- Kafka 기반 비동기 문서 색인
- Docker 기반 실행 환경
- CI/CD
- 모니터링 및 성능 측정

## 개발 원칙

- 처음부터 MSA로 구성하지 않는다.
- 먼저 단순한 구조로 기능을 구현한다.
- Redis와 Kafka는 필요성이 확인된 후 도입한다.
- 기술 도입 전후의 성능과 구조 변화를 기록한다.
- 새로운 기술을 사용하는 것보다 도입 이유를 설명할 수 있는 것을 우선한다.

## 제외 범위

초기 MVP에서는 아래 기능을 구현하지 않는다.

- Kafka
- Redis
- Microservice Architecture
- 복잡한 관리자 기능
- AI 모델 자체 학습