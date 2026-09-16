"""F-AI-007. Backend<->AI Service 내부 계약 전용 Pydantic Model.

필드 이름은 Java(Jackson) 쪽 Record 이름과 그대로 맞춘 camelCase다(내부
Service-to-Service 계약이므로 Python 관례보다 두 언어 간 불일치 위험을
줄이는 쪽을 우선한다) - Alias 설정을 따로 두지 않는다.
"""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel

# ContentExtractionService/ContentProcessingPolicy(Java)의 ParseOutcomeKind와
# 정확히 같은 네 값이다 - 여기서 새 값을 추가하지 않는다.
OUTCOME_SUCCESS = "SUCCESS"
OUTCOME_UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
OUTCOME_NO_TEXT = "NO_TEXT"
OUTCOME_FAILED = "FAILED"


class LocationDto(BaseModel):
    """LocatorType(Java)과 동일한 여섯 값 중 하나(locatorType)."""

    locatorType: str
    locatorValue: str
    startOffset: int
    endOffset: int


class ChunkCoordinateDto(BaseModel):
    """Transient deterministic chunk coordinate; chunk plaintext is never returned."""

    chunkIndex: int
    locatorType: str
    locatorValue: str
    startOffset: int
    endOffset: int


class ParseResponseDto(BaseModel):
    """outcome이 SUCCESS일 때만 parser*/normalized*/locations가 채워진다.

    reason은 실패/미지원/텍스트 없음일 때만 채워지며, 짧고 고정된 값이어야
    한다 - 원본 파일 내용이나 예외 Stack Trace를 절대 담지 않는다.
    """

    outcome: str
    parserName: Optional[str] = None
    parserVersion: Optional[str] = None
    normalizationVersion: Optional[str] = None
    normalizedText: Optional[str] = None
    locations: Optional[List[LocationDto]] = None
    chunkingVersion: Optional[str] = None
    embeddingModel: Optional[str] = None
    chunks: Optional[List[ChunkCoordinateDto]] = None
    reason: Optional[str] = None


class HealthResponseDto(BaseModel):
    status: str


class IndexChunkDto(BaseModel):
    """M11 신규 - 청크 하나의 색인 결과. 평문 Chunk Text를 절대 담지 않는다 -
    Embedding Vector/일반화된 Locator/원문을 복원할 수 없는 Content HMAC뿐이다."""

    chunkIndex: int
    locatorType: str
    locatorValue: str
    embedding: List[float]
    contentHmac: str


class EmbedQueryRequestDto(BaseModel):
    """M12 신규(F-AI-embed-query) - Vector Candidate 검색을 위한 질의(Query) 문장
    하나를 Embedding으로 변환하는 요청. 질의 원문은 이 요청/응답 계약 밖 어디에도
    (로그/DB/Cache) 남지 않는다 - 처리 즉시 버려지는 일시적 값이다."""

    text: str


class EmbedQueryResponseDto(BaseModel):
    """outcome이 SUCCESS일 때만 embedding/embeddingModel/embeddingDimensions가
    채워진다. reason은 실패일 때만 채워지며 짧고 고정된 값이어야 한다(질의 원문을
    절대 담지 않는다)."""

    outcome: str
    embedding: Optional[List[float]] = None
    embeddingModel: Optional[str] = None
    embeddingDimensions: Optional[int] = None
    reason: Optional[str] = None


class IndexResponseDto(BaseModel):
    """M11 신규(F-AI-index) - {@code /index} 응답. outcome이 SUCCESS일 때만
    parserVersion/chunkingVersion/embeddingModel/embeddingDimensions/chunks가
    채워진다 - Java DocumentParsingClient.index()가 그대로 IndexOutcome으로 옮긴다.
    reason은 실패/미지원/텍스트 없음일 때만 채워지며, /parse와 동일하게 짧고 고정된
    값이어야 한다(원본 파일 내용/예외 Stack Trace 금지)."""

    outcome: str
    parserVersion: Optional[str] = None
    chunkingVersion: Optional[str] = None
    embeddingModel: Optional[str] = None
    embeddingDimensions: Optional[int] = None
    chunks: Optional[List[IndexChunkDto]] = None
    reason: Optional[str] = None
