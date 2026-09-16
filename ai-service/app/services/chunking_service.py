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
from app.models.schemas import LocationDto

CHUNKING_VERSION = "1"


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


def _locator_for_offset(locations: List[LocationDto], offset: int) -> LocationDto:
    """``offset``(코드포인트 단위)을 포함하는(또는 그 직전에서 시작하는) Location을
    찾는다. Location은 Parser가 발생 순서대로 반환한다고 가정한다(모든 현재
    Parser가 그렇게 만든다) - 정확히 일치하는 코드포인트/UTF-16 단위 차이는
    최악의 경우 Non-BMP 문자 근처에서 인접 Locator로 살짝 치우칠 수 있는, 알려진
    사소한 근사치다(Locator는 이미 일반화된 좌표일 뿐이라 보안/정확성에 영향을
    주지 않는다 - 남은 한계로 문서화한다)."""

    chosen = locations[0]
    for location in locations:
        if location.startOffset <= offset:
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
        locator = _locator_for_offset(locations, start)
        content_hmac = compute_content_hmac(chunk_text_value, hmac_key)
        chunks.append(Chunk(index, locator.locatorType, locator.locatorValue, chunk_text_value, content_hmac))
    return chunks
