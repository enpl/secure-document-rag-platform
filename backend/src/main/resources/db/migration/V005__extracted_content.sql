-- ============================================================
-- Secure Document Vault
-- V005 : Content Processing - Extracted Text Storage
--
-- 역할:
--   M06(Content Processing Core)이 Python AI Service의 파싱 결과를
--   저장하는 document_extracted_content 테이블을 추가한다. 문서당
--   "현재 사용 가능한 결과" 하나만 유지한다(이력 테이블이 아니다).
--
-- 동시성/일관성 모델 (자세한 설명은 ContentExtractionService,
-- DocumentExtractedContentJpaRepository의 Javadoc 참고):
--   - attempt_id / attempt_started_at : 짧은 Claim 트랜잭션이 이 문서에
--     대한 배타적 처리 권한을 원자적으로 획득/해제하기 위한 최소 상태다.
--     느린 외부 Parsing 호출 동안에는 어떤 DB Transaction/Lock도 걸어
--     두지 않는다 - Claim 트랜잭션과 Finalize 트랜잭션만 각각 짧게
--     연다. 오래된(예: Worker Crash로 방치된) Claim은
--     attempt_started_at 기준 Staleness 임계값이 지나면 다른 시도가
--     재점유할 수 있다.
--   - 실제 "현재 발행된(Published) 결과" 컬럼(content_hash 이하)은
--     성공한 Finalize 한 번에 전부 함께 채워진다 - 행이 존재한다는
--     사실만으로는 사용 가능한 결과가 있다는 증거가 되지 않는다
--     (Claim 중인 빈 행일 수 있다). published_at이 NOT NULL인지로
--     판단한다.
--
-- 주의:
--   V001~V004는 수정하지 않는다.
--   document_chunks(V002, embedding vector(1024) NOT NULL)는 건드리지
--   않는다 - 이 테이블은 Embedding 이전 단계의 정규화된 텍스트만
--   담는다.
-- ============================================================

CREATE TABLE document_extracted_content (

    -- source_documents와 1:1 - 문서당 현재 결과는 최대 하나다.
    document_id BIGINT PRIMARY KEY,

    -- ----------------------------------------------------------
    -- Claim(배타적 처리 권한) 상태 - 발행된 결과와 무관하게 독립적으로
    -- NULL <-> NOT NULL을 오간다.
    -- ----------------------------------------------------------
    attempt_id UUID,
    attempt_started_at TIMESTAMPTZ,

    -- ----------------------------------------------------------
    -- 발행된(Published) 결과 - 아래 전부가 함께 NULL이거나 함께
    -- NOT NULL이다(chk_extracted_content_published_together).
    -- ----------------------------------------------------------

    -- 이 결과를 만들어낸 시점의 source_documents.source_version 스냅샷.
    source_version VARCHAR(255),

    -- Fetch한 원본 바이트의 SHA-256 Hex Digest(64자) - 내용 동일성 증거일
    -- 뿐, "최신 Source 버전"의 증거는 아니다.
    content_hash VARCHAR(64),

    -- 예: pdfminer.six, python-docx, openpyxl, plaintext
    parser_name VARCHAR(100),
    parser_version VARCHAR(50),

    -- SDV 자체 텍스트 정규화 로직의 식별자 - parser_version과 독립적으로
    -- 바뀔 수 있다.
    normalization_version VARCHAR(50),

    -- 정규화된 추출 텍스트 전체 - 성공한 결과는 절대 잘리지 않는다
    -- (출력 길이 상한을 넘기면 성공이 아니라 실패로 분류된다).
    normalized_text TEXT,

    -- Citation 재구성을 위한 구조적 위치 정보 - V003
    -- document_chunks.locator_type과 동일한 어휘(PAGE/SLIDE/SHEET_RANGE/
    -- LINE_RANGE/SECTION/DOCUMENT)를 사용하는 JSON 배열 텍스트
    -- ([{locatorType, locatorValue, startOffset, endOffset}, ...]),
    -- offset은 normalized_text의 UTF-16 code unit 인덱스(Java String과
    -- 동일 단위)다. Postgres 질의로 JSON 내부를 조회할 필요가 없으므로
    -- (Application이 항상 통째로 읽고/쓴다) JSONB 대신 TEXT로 저장해
    -- JDBC/Hibernate JSON 타입 매핑의 불확실성을 피한다.
    locations TEXT,

    published_at TIMESTAMPTZ,

    CONSTRAINT fk_extracted_content_document
        FOREIGN KEY (document_id)
            REFERENCES source_documents(id)
            ON DELETE CASCADE,

    CONSTRAINT chk_extracted_content_published_together
        CHECK (
            (published_at IS NULL
                AND source_version IS NULL
                AND content_hash IS NULL
                AND parser_name IS NULL
                AND parser_version IS NULL
                AND normalization_version IS NULL
                AND normalized_text IS NULL
                AND locations IS NULL)
            OR
            (published_at IS NOT NULL
                AND source_version IS NOT NULL
                AND content_hash IS NOT NULL
                AND parser_name IS NOT NULL
                AND parser_version IS NOT NULL
                AND normalization_version IS NOT NULL
                AND normalized_text IS NOT NULL
                AND locations IS NOT NULL)
        )
);
