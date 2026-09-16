"""M11 신규(F-AI-index) - 정규화된 Parser 출력을 결정론적(Deterministic)으로
Chunk하고, 각 Chunk를 이미 Parser가 만든 Location에 매핑하며, 원문을 복원할 수
없는 Keyed Content HMAC을 계산한다.

핵심 보안/설계 경계:
  - 이 파일이 만든 Chunk의 평문 Text는 절대 HTTP 응답으로 나가지 않는다
    (``index_worker.py``가 Embedding 계산 직후 버린다) - 오직 이 파일 안에서만
    잠깐 존재한다.
  - Chunking은 완전히 결정론적이다(같은 입력 + 같은 설정이면 항상 같은 경계) -
    무작위/모델 기반 분할을 쓰지 않는다.
  - Locator는 Parser가 이미 만든 위치(페이지/문서 전체 등)를 그대로 재사용한다 -
    새로운 문서 구조 이해를 시도하지 않고, 제목/문장 같은 Content 자체를 담지
    않는다("generalized coordinates, not quoted headings or content snippets").
  - Content HMAC은 외부에서 주입된 Key로만 계산한다 - Key가 없으면(운영자가 아직
    설정하지 않았으면) 이 파일은 예외를 던지지 않고 호출자가 명시적으로 실패를
    선택할 수 있도록 ``None``을 반환한다(Key 존재 여부 확인은 호출자 책임).
"""

from __future__ import annotations

import hashlib
import hmac as hmac_module
from typing import List, NamedTuple, Optional, Tuple

from app.core.config import Settings
from app.models.schemas import ChunkCoordinateDto, LocationDto

# M12 교정으로 "2"로 올렸다 - Chunk 경계를 Locator에 매핑할 때 Python 코드포인트
# 오프셋과 Location의 UTF-16 오프셋을 섞어 비교하던 결함을 고쳤다(비-BMP 문자
# 근처에서 잘못된 Page/Section에 배정될 수 있었다). "1"로 만들어진 기존 Generation은
# 이 수정 이전 규칙으로 만들어졌으므로 호환되지 않는 것으로 취급해야 한다
# (DocumentEmbeddingJpaRepository.replaceGeneration의 Generation 동질성 검증이
# chunkingVersion도 함께 확인한다 - 기존 스케줄링 경로를 통한 재색인이 자연히
# "1" 세대를 "2" 세대로 교체한다, 별도 Migration/Backfill 강제 없음).
CHUNKING_VERSION = "2"


def _utf16_offset(text: str, codepoint_offset: int) -> int:
    """``parser_service._utf16_offset``와 동일한 변환(같은 Wire 단위 규약) -
    Location의 ``startOffset``/``endOffset``는 이미 UTF-16 Code Unit 단위로
    변환돼 있으므로(``ExtractedLocation`` Javadoc 참고), 이 Chunk 경계
    (Python ``str`` 코드포인트 단위)를 같은 단위로 바꾼 뒤에만 비교해야 한다."""

    return len(text[:codepoint_offset].encode("utf-16-le")) // 2


class Chunk(NamedTuple):
    """Chunking 결과 한 건 - ``text``는 Embedding 계산 직후 즉시 버려야 한다(호출자 책임)."""

    chunk_index: int
    locator_type: str
    locator_value: str
    text: str
    content_hmac: str


def compute_content_hmac(text: str, key: bytes) -> str:
    """Keyed Digest(HMAC-SHA256) Hex - 원문을 복원할 수 없다. 동일 Content
    재확인/재사용 판단용일 뿐이다(v1.4 §2A.2/§2A.3)."""

    return hmac_module.new(key, text.encode("utf-8"), hashlib.sha256).hexdigest()


def _windows(length: int, chunk_size: int, overlap: int) -> List[Tuple[int, int]]:
    """결정론적 Sliding Window - 매 호출 항상 같은 경계를 만든다. ``chunk_size``가
    유효하지 않으면(0 이하) 빈 목록을 반환한다(호출자가 실패로 처리한다)."""

    if chunk_size <= 0 or length <= 0:
        return []
    step = max(1, chunk_size - max(0, overlap))
    windows: List[Tuple[int, int]] = []
    start = 0
    while start < length:
        end = min(length, start + chunk_size)
        windows.append((start, end))
        if end >= length:
            break
        start += step
    return windows


def _locator_for_offset(locations: List[LocationDto], utf16_offset: int) -> LocationDto:
    """``utf16_offset``(UTF-16 Code Unit 단위 - Location의 ``startOffset``와
    동일한 Wire 단위 규약, ``ExtractedLocation``/``parser_service._utf16_offset``
    참고)을 포함하는(또는 그 직전에서 시작하는) Location을 찾는다. 호출자
    (``chunk_text``)가 반드시 Chunk 경계를 이 단위로 변환해 넘겨야 한다 - 코드포인트
    오프셋을 그대로 넘기면 Non-BMP 문자(예: 이모지) 앞뒤에서 잘못된 Locator로
    Chunk가 배정될 수 있었다(M12 교정 - 이전에는 이 두 단위가 섞여 비교됐다).
    Location은 Parser가 발생 순서대로 반환한다고 가정한다(모든 현재 Parser가
    그렇게 만든다)."""

    chosen = locations[0]
    for location in locations:
        if location.startOffset <= utf16_offset:
            chosen = location
        else:
            break
    return chosen


def chunk_text(text: str, locations: List[LocationDto], settings: Settings,
        hmac_key: Optional[bytes]) -> Optional[List[Chunk]]:
    """``text``를 결정론적으로 Chunk하고 각 Chunk를 Locator에 매핑한다.

    실패(호출자가 FAILED로 처리해야 함) 조건:
      - ``hmac_key``가 없다(운영자가 Content HMAC Key를 아직 설정하지 않음).
      - ``locations``가 비어있다(매핑할 위치가 전혀 없음 - Parser 계약 위반).
      - 결과 Chunk 수가 ``settings.max_chunks_per_document``를 넘는다(잘라내지
        않는다 - 잘린 결과를 성공으로 포장하지 않는다).
    """

    if hmac_key is None or not locations:
        return None
    windows = _windows(len(text), settings.chunk_max_chars, settings.chunk_overlap_chars)
    if not windows:
        return None
    if len(windows) > settings.max_chunks_per_document:
        return None

    chunks: List[Chunk] = []
    for index, (start, end) in enumerate(windows):
        chunk_text_value = text[start:end]
        locator = _locator_for_offset(locations, _utf16_offset(text, start))
        content_hmac = compute_content_hmac(chunk_text_value, hmac_key)
        chunks.append(Chunk(index, locator.locatorType, locator.locatorValue, chunk_text_value, content_hmac))
    return chunks


def chunk_coordinates(text: str, locations: List[LocationDto], settings: Settings) -> Optional[List[ChunkCoordinateDto]]:
    """Expose only the same deterministic index chunk boundaries for live evidence selection."""
    if not locations:
        return None
    windows = _windows(len(text), settings.chunk_max_chars, settings.chunk_overlap_chars)
    if not windows or len(windows) > settings.max_chunks_per_document:
        return None
    return [ChunkCoordinateDto(
        chunkIndex=index,
        locatorType=(locator := _locator_for_offset(locations, _utf16_offset(text, start))).locatorType,
        locatorValue=locator.locatorValue,
        startOffset=_utf16_offset(text, start),
        endOffset=_utf16_offset(text, end),
    ) for index, (start, end) in enumerate(windows)]
