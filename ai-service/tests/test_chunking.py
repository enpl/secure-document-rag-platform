"""M11 (F-AI-index) - chunking_service의 결정론적 분할/Locator 매핑/Content HMAC 검증."""

from __future__ import annotations

from app.core.config import Settings
from app.models.schemas import LocationDto
from app.services import chunking_service


def _settings(**overrides) -> Settings:
    base = dict(chunk_max_chars=10, chunk_overlap_chars=2, max_chunks_per_document=50)
    base.update(overrides)
    return Settings(**base)


def test_chunking_is_deterministic_across_repeated_calls():
    text = "abcdefghijklmnopqrstuvwxyz"
    locations = [LocationDto(locatorType="DOCUMENT", locatorValue="1", startOffset=0, endOffset=len(text))]
    key = b"synthetic-test-key"

    first = chunking_service.chunk_text(text, locations, _settings(), key)
    second = chunking_service.chunk_text(text, locations, _settings(), key)

    assert first is not None and second is not None
    assert [c.text for c in first] == [c.text for c in second]
    assert [c.content_hmac for c in first] == [c.content_hmac for c in second]


def test_chunking_covers_the_whole_text_with_overlap_between_consecutive_windows():
    text = "0123456789" * 3  # 30 chars
    locations = [LocationDto(locatorType="DOCUMENT", locatorValue="1", startOffset=0, endOffset=len(text))]

    chunks = chunking_service.chunk_text(text, locations, _settings(chunk_max_chars=10, chunk_overlap_chars=3), b"k")

    assert chunks is not None
    # 마지막 Chunk는 항상 Text 끝에서 끝난다 - 잘려나가는 꼬리가 없다.
    assert chunks[-1].text.endswith(text[-1])
    # 인접 Chunk 사이에 실제로 겹치는 구간이 있다(경계 근처 문맥 유실 완화).
    assert chunks[0].text[-3:] == chunks[1].text[:3]


def test_chunk_maps_to_the_locator_that_encloses_its_start_offset():
    text = "page-one-text" + "page-two-text"
    locations = [
        LocationDto(locatorType="PAGE", locatorValue="1", startOffset=0, endOffset=13),
        LocationDto(locatorType="PAGE", locatorValue="2", startOffset=13, endOffset=len(text)),
    ]

    chunks = chunking_service.chunk_text(text, locations, _settings(chunk_max_chars=5, chunk_overlap_chars=0), b"k")

    assert chunks is not None
    first_chunk_locators = {c.locator_value for c in chunks if c.chunk_index == 0}
    last_chunk = chunks[-1]
    assert first_chunk_locators == {"1"}
    assert last_chunk.locator_value == "2"


def test_chunking_returns_none_when_hmac_key_is_missing():
    text = "some text"
    locations = [LocationDto(locatorType="DOCUMENT", locatorValue="1", startOffset=0, endOffset=len(text))]

    result = chunking_service.chunk_text(text, locations, _settings(), None)

    assert result is None


def test_chunking_returns_none_when_there_are_no_locations_to_map_onto():
    result = chunking_service.chunk_text("some text", [], _settings(), b"k")

    assert result is None


def test_chunking_fails_closed_instead_of_silently_truncating_when_over_the_chunk_count_limit():
    text = "x" * 1000
    locations = [LocationDto(locatorType="DOCUMENT", locatorValue="1", startOffset=0, endOffset=len(text))]

    result = chunking_service.chunk_text(text, locations, _settings(chunk_max_chars=10, chunk_overlap_chars=0,
            max_chunks_per_document=5), b"k")

    assert result is None


def test_chunk_locator_is_correct_across_a_non_bmp_character_boundary():
    """M12 교정 - 이전에는 Chunk 경계(Python 코드포인트 단위)를 Location의
    ``startOffset``(UTF-16 Code Unit 단위)와 단위 변환 없이 직접 비교했다.
    Non-BMP 문자(예: 이모지, UTF-16에서 Surrogate Pair 2 Unit)가 Page 1
    경계 바로 앞에 있으면, 코드포인트 오프셋이 UTF-16 오프셋보다 작게
    계산되어 실제로는 Page 2에 속하는 Chunk가 Page 1로 잘못 배정될 수
    있었다. 이 Test는 그 정확한 상황을 재현한다."""

    # Page 1: 한글 5자 + 이모지(Non-BMP, UTF-16 2 Unit) = 코드포인트 6개지만 UTF-16 7 Unit.
    page_one = "가나다라마" + "\U0001F600"
    page_two = "바사아자차"
    text = page_one + page_two
    page_one_utf16_len = len(page_one.encode("utf-16-le")) // 2  # == 7
    locations = [
        LocationDto(locatorType="PAGE", locatorValue="1", startOffset=0, endOffset=page_one_utf16_len),
        LocationDto(locatorType="PAGE", locatorValue="2", startOffset=page_one_utf16_len,
                endOffset=page_one_utf16_len + len(page_two)),
    ]
    # chunk_max_chars=6(코드포인트 단위)이면 두 번째 Chunk가 코드포인트 offset 6에서
    # 시작한다 - UTF-16 offset으로는 7이라 정확히 Page 2 경계와 일치해야 한다.
    chunks = chunking_service.chunk_text(text, locations, _settings(chunk_max_chars=6, chunk_overlap_chars=0), b"k")

    assert chunks is not None
    assert len(chunks) == 2
    assert chunks[0].locator_value == "1"
    assert chunks[0].text == page_one
    assert chunks[1].locator_value == "2"
    assert chunks[1].text == page_two


def test_content_hmac_is_keyed_and_differs_for_different_keys():
    text = "identical content"

    hmac_a = chunking_service.compute_content_hmac(text, b"key-a")
    hmac_b = chunking_service.compute_content_hmac(text, b"key-b")

    assert hmac_a != hmac_b
    assert len(hmac_a) == 64  # SHA-256 hex digest length - matches document_embedding_index.content_hmac VARCHAR(64).
