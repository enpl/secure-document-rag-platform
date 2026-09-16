"""M11 신규(F-AI-index) - {@code /index} 요청 하나의 전체 Pipeline을 조립한다:
이미 검증된 {@code parser_service.parse_document}(재사용, 재구현하지 않음) ->
{@code chunking_service.chunk_text} -> {@code embedding_service.embed_texts} ->
평문이 전혀 없는 {@link IndexResponseDto} 조립.

이 파일 밖으로는 어떤 정규화된 Text/Chunk Text도 나가지 않는다 - 이 함수가
반환하는 값에는 Embedding Vector/일반화된 Locator/Content HMAC/Version
메타데이터만 있다. Python 프로세스 안에서 그 평문이 살아있는 구간은 이 함수
호출 하나로 한정된다(호출이 끝나면 지역 변수 참조가 사라진다 - Python 문자열은
불변이라 Java의 명시적 0-채움 같은 물리적 소거를 보장할 수 없다는 한계는 그대로
남는다, ``chunking_service``/``embedding_service`` Docstring 참고).
"""

from __future__ import annotations

import base64
import binascii
from typing import Optional

from app.core.config import Settings
from app.models.schemas import (
    OUTCOME_FAILED,
    OUTCOME_SUCCESS,
    IndexChunkDto,
    IndexResponseDto,
)
from app.services import chunking_service, embedding_service
from app.services.parser_service import parse_document


def _decode_hmac_key(raw_key: Optional[str]) -> Optional[bytes]:
    """운영자가 준 Key 문자열을 Byte로 변환한다 - Base64로 먼저 시도하고(운영
    환경의 흔한 관례), 실패하면 UTF-8 그대로 쓴다(합성 Test Key는 보통 평범한
    문자열이다). 빈 문자열/None은 "설정되지 않음"으로 취급한다(하드코딩된 기본
    Key로 조용히 대체하지 않는다)."""

    if not raw_key:
        return None
    try:
        decoded = base64.b64decode(raw_key, validate=True)
        if decoded:
            return decoded
    except (binascii.Error, ValueError):
        pass
    return raw_key.encode("utf-8")


def run_index(content: bytes, file_name: str, declared_mime_type: Optional[str],
        settings: Settings) -> IndexResponseDto:
    """Parse가 성공(SUCCESS)이 아니면 그 Outcome/Reason을 그대로 옮긴다(재구현
    없음) - Chunking/Embedding은 성공적인 Parse에 대해서만 시도한다. 이후 모든
    실패 경로(Key 미설정/Chunking 실패/Embedding Provider 불가)는 FAILED로
    안전하게 끝난다 - 절대 근거 없이 SUCCESS를 만들지 않는다."""

    parsed = parse_document(content, file_name, declared_mime_type, settings)
    if parsed.outcome != OUTCOME_SUCCESS:
        return IndexResponseDto(outcome=parsed.outcome, reason=parsed.reason)

    hmac_key = _decode_hmac_key(settings.index_content_hmac_key)
    if hmac_key is None:
        return IndexResponseDto(outcome=OUTCOME_FAILED, reason="content hmac key not configured")

    chunks = chunking_service.chunk_text(parsed.normalizedText, parsed.locations or [], settings, hmac_key)
    if not chunks:
        return IndexResponseDto(outcome=OUTCOME_FAILED, reason="chunking failed or produced no chunks")

    embeddings = embedding_service.embed_texts([chunk.text for chunk in chunks], settings)
    if embeddings is None:
        return IndexResponseDto(outcome=OUTCOME_FAILED,
                reason="embedding provider unavailable or returned a malformed response")

    chunk_dtos = [
        IndexChunkDto(
            chunkIndex=chunk.chunk_index,
            locatorType=chunk.locator_type,
            locatorValue=chunk.locator_value,
            embedding=vector,
            contentHmac=chunk.content_hmac,
        )
        for chunk, vector in zip(chunks, embeddings)
    ]

    return IndexResponseDto(
        outcome=OUTCOME_SUCCESS,
        parserVersion=parsed.parserVersion,
        chunkingVersion=chunking_service.CHUNKING_VERSION,
        embeddingModel=embedding_service.EMBEDDING_MODEL,
        embeddingDimensions=embedding_service.EMBEDDING_DIMENSIONS,
        chunks=chunk_dtos,
    )
