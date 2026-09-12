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
    reason: Optional[str] = None


class HealthResponseDto(BaseModel):
    status: str
