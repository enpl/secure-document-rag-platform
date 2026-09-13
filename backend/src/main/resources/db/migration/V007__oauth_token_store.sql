-- ============================================================
-- Secure Document Vault
-- V007 : OAuth Token Store (M08 MVP OAuth, docs/spec/SDV_M08_TOKEN_CONTRACT.md)
--
-- 역할:
--   Google OAuth Access/Refresh Token을 PostgreSQL에 AES-256-GCM 암호문으로
--   저장하는 전용 테이블을 새로 만든다. source_connections.token_ref(V001)는
--   이 테이블 행을 가리키는 opaque UUID 참조 문자열만 계속 담으며, 원본이든
--   암호화된 형태든 Token 자체를 절대 담지 않는다(docs/spec/SDV_v3.2_CORE_SPEC.md
--   §9 "raw token must not be stored in general plaintext DB fields",
--   "source_connections.token_ref is a token reference, not the raw token"
--   규칙을 V007 시점에도 그대로 유지).
--
-- 이 테이블이 담지 않는 것(중요):
--   - 평문 accessToken/refreshToken - TokenEnvelope 전체를 직렬화한 뒤
--     AES/GCM으로 암호화한 ciphertext 컬럼 안에만 존재한다.
--   - Master Key 자체 - Key는 이 Migration이나 어떤 DB 행에도 저장하지
--     않는다. Runtime 설정(SecretProperties, 환경변수/보호된 Secret 파일)이
--     매 기동 시 외부에서 공급한다.
--   - 문서 원문/질문/답변/Prompt - 이 테이블은 M08 OAuth 범위 밖의 어떤
--     Content/Evidence도 담지 않는다(원본 비보관 규칙과 무관한 별도 테이블).
--
-- 주의:
--   V001~V006은 수정하지 않는다(Immutable, 이미 적용됨). 이 Migration은
--   V001의 source_connections.token_ref 컬럼 자체를 변경하지 않는다 - 그
--   컬럼에 저장되는 "값의 의미"만 이 시점부터 실제로 쓰이기 시작한다.
-- ============================================================

CREATE TABLE source_oauth_tokens (
    -- GoogleTokenService가 생성하는 불투명 Random UUID. 같은 값의 문자열
    -- 표현이 source_connections.token_ref(V001, VARCHAR(500))에도 저장된다 -
    -- 두 값이 일치하는지는 Application(GoogleTokenService.load)이 Load 시점마다
    -- 다시 확인한다("Resolve using Source ID, expected owner and stored
    -- reference together; swapping a reference must fail closed").
    token_ref UUID PRIMARY KEY,

    -- Source 하나당 활성 Token은 최대 하나(1:1) - 재연결/Refresh 시 기존 행을
    -- 교체(UPDATE)한다. source_connections 행 자체는 Disconnect 시에도 논리
    -- 삭제(status=DISABLED)만 될 뿐 물리적으로 삭제되지 않으므로(M04
    -- SourceConnectionService.disconnect), 아래 CASCADE는 정상 흐름에서는
    -- 발동하지 않는다 - 그래도 향후 source_connections 행이 실제로 삭제되는
    -- 경로가 생기더라도 암호화된 Token이 소유자 없는 고아 행으로 남지
    -- 않도록 방어적으로 CASCADE를 둔다(V006 document_embedding_index와
    -- 동일한 관례).
    source_id BIGINT NOT NULL UNIQUE
        REFERENCES source_connections(id)
        ON DELETE CASCADE,

    -- 이 Token을 실제로 발급받은 SDV 인증 Subject(TokenEnvelope.boundSubject와
    -- 항상 같은 값) - GoogleDriveConnector가 source_connections.owner_subject와
    -- 별도로 재확인하는 근거(M08 Review 교정 항목 7과 동일한 방어 심층화를
    -- 이 테이블 자체에도 한 번 더 둔다).
    owner_subject VARCHAR(255) NOT NULL,

    -- 암호화 Payload(TokenEnvelope 직렬화 형식)의 구조 버전 - 형식이 바뀌면
    -- 증가시킨다. 오늘은 1 하나만 쓰인다.
    format_version INTEGER NOT NULL,

    -- 이 행을 암호화한 Master Key의 식별자. MVP는 활성 Key 하나만 지원한다 -
    -- 자동 Key Rotation/재암호화 도구는 배포 전 별도 작업으로 이연한다
    -- (docs/plan/SDV_MVP_DEFERRED.md MVP-10). key_id가 현재 설정된 활성
    -- Key와 다르면 GoogleTokenService.load는 복호화를 시도하지 않고 Fail
    -- Closed 한다 - 이 시점의 최소 복구 절차는 관리자가 올바른 Key를 다시
    -- 설정하거나, 그럴 수 없으면 사용자가 재인증(Reconnect)하는 것이다.
    key_id VARCHAR(100) NOT NULL,

    -- AES/GCM/NoPadding 12-byte Nonce - 같은 Key와 함께 절대 재사용하지
    -- 않는다(매 암호화마다 SecureRandom으로 새로 생성, GoogleTokenService).
    nonce BYTEA NOT NULL,

    -- AES/GCM Ciphertext(128-bit 인증 Tag 포함, JDK Cipher가 Tag를 Ciphertext
    -- 끝에 덧붙이는 표준 동작을 그대로 저장한다). 복호화하면 직렬화된
    -- TokenEnvelope(accessToken/refreshToken/expiresAt/scopes)가 나온다 -
    -- 평문 Token은 이 컬럼이나 다른 어떤 컬럼에도 저장하지 않는다.
    ciphertext BYTEA NOT NULL,

    -- 낙관적 잠금(JPA @Version) - Refresh/Disconnect/재연결 경합을 감지한다.
    row_version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
