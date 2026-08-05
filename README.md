# secure-document-rag-platform
A secure document search and RAG platform built with Spring Boot, Spring AI, React, Kafka, and Redis.

## 프로젝트 목표

- Spring AI 기반 문서 질의응답
- PDF 문서 등록 및 Vector DB 검색
- 사용자별 문서 접근 권한
- React·TypeScript 기반 관리 화면
- Kafka 기반 문서 색인 비동기 처리
- Redis 캐시 및 요청 제한
- 모니터링과 장애 대응
- Docker 기반 실행 및 배포

## 개발 단계

- [ ] Spring AI 기본 질의응답
- [ ] PDF 기반 RAG
- [ ] React 사용자 화면
- [ ] 사용자 인증과 문서 접근 권한
- [ ] Redis 캐시
- [ ] Kafka 비동기 색인
- [ ] 모니터링과 부하 테스트
- [ ] Docker 및 CI/CD
- [ ] v1.0.0 배포

## 브랜치 전략

- `main`: 배포 및 공개 가능한 안정 버전
- `develop`: 다음 버전 통합 개발
- `feature/*`: 기능 개발
- `fix/*`: 일반 버그 수정
- `release/*`: 출시 준비
- `hotfix/*`: 운영 버전 긴급 수정

## 저장소 구조

```text
backend/    Spring Boot 백엔드
frontend/   React·TypeScript 프론트엔드
infra/      Docker, Keycloak, 모니터링 설정
docs/       아키텍처와 기술 기록
samples/    테스트용 공개 문서와 요청 예시
scripts/    개발·실행 보조 스크립트