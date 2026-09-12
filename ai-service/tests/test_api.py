"""F-AI-002 API 계약 검증 - FastAPI TestClient로 실제 서비스를 띄우지 않고
In-Process로 {@code /parse}/{@code /health}를 검증한다."""

from __future__ import annotations

import threading
import time

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

from app.api import routes
from app.core.config import Settings
from app.main import app
from app.services import parser_service as ps

client = TestClient(app)


def test_health_returns_ok():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "OK"}


def test_parse_returns_success_for_plain_text():
    files = {"file": ("notes.txt", b"hello api world", "text/plain")}
    data = {"declaredMimeType": "text/plain"}

    response = client.post("/parse", files=files, data=data)

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "SUCCESS"
    assert body["normalizedText"] == "hello api world"


def test_parse_returns_unsupported_format_for_legacy_doc_extension():
    files = {"file": ("legacy.doc", b"whatever bytes", "application/msword")}
    data = {"declaredMimeType": "application/msword"}

    response = client.post("/parse", files=files, data=data)

    assert response.status_code == 200
    assert response.json()["outcome"] == "UNSUPPORTED_FORMAT"


def test_parse_rejects_file_content_exceeding_the_configured_limit():
    # 요청 전체(Multipart Envelope 포함)는 작다 - 이 경우는 Network 수신
    # 단계가 아니라, 실제로 추출된 File 내용 길이가 (일부러 아주 작게 잡은)
    # max_input_bytes를 넘는 경우다. parse_document 자신의 검사가 이를
    # 잡는다(HTTP 200 + outcome=FAILED - Network 단의 413과는 다른 경로,
    # 아래 test_parse_rejects_an_oversized_network_body_with_413이 그
    # 경로를 별도로 검증한다).
    original = routes.get_settings
    try:
        routes.get_settings = lambda: Settings(max_input_bytes=10)
        files = {"file": ("big.txt", b"0123456789ABCDEFGHIJ", "text/plain")}

        response = client.post("/parse", files=files, data={"declaredMimeType": "text/plain"})

        assert response.status_code == 200
        body = response.json()
        assert body["outcome"] == "FAILED"
        assert "input size" in (body["reason"] or "")
    finally:
        routes.get_settings = original


def test_parse_rejects_an_oversized_network_body_with_413_before_full_spooling():
    # 이번에는 Request Body 자체(Multipart Envelope 포함)가 (일부러 작게
    # 잡은) 한도+Envelope Overhead를 실제로 넘는 경우 - Network 수신 자체를
    # 초과 즉시 중단해야 한다(413). 아래
    # test_read_bounded_body_stops_consuming_the_stream_once_over_limit이
    # "Buffering 이후가 아니라 수신 도중"이라는 부분을 별도로, 더 직접
    # 증명한다(HTTP Client를 거치면 Chunk 소비 횟수를 관찰할 수 없어서).
    original = routes.get_settings
    try:
        routes.get_settings = lambda: Settings(max_input_bytes=1_000)
        big_content = b"X" * 200_000
        files = {"file": ("big.txt", big_content, "text/plain")}

        response = client.post("/parse", files=files, data={"declaredMimeType": "text/plain"})

        assert response.status_code == 413
    finally:
        routes.get_settings = original


def test_read_bounded_body_stops_consuming_the_stream_once_over_limit():
    """Chunked 방식으로 도착하는 원시 ASGI Stream을 흉내 낸 Synthetic
    receive Callable로, `_read_bounded_body`가 한도를 넘는 즉시(전체를 다
    받기 전에) 멈추는지 최종 HTTP 상태가 아니라 실제 소비한 Chunk 수로
    직접 증명한다."""
    chunk = b"a" * 100
    total_chunks = 1000  # 총 100,000 bytes - 아래 한도(500)를 크게 초과한다.
    consumed = {"count": 0}

    async def fake_receive():
        if consumed["count"] >= total_chunks:
            return {"type": "http.request", "body": b"", "more_body": False}
        consumed["count"] += 1
        more = consumed["count"] < total_chunks
        return {"type": "http.request", "body": chunk, "more_body": more}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/parse",
        "headers": [],
        "query_string": b"",
    }
    request = Request(scope, fake_receive)

    with pytest.raises(Exception):
        _run_async(routes._read_bounded_body(request, max_bytes=500))

    # 100,000 byte를 다 받았다면 1000번 소비했을 것이다 - 훨씬 적게
    # 소비하고 멈췄다는 것이 "Buffering 이후가 아니라 수신 도중 거부"의
    # 직접적 증거다.
    assert consumed["count"] < total_chunks


def _run_async(coro):
    import asyncio

    return asyncio.run(coro)


def test_slow_parse_does_not_block_health():
    """느린 Parsing이 Event Loop를 막으면 안 된다 - `/parse` 하나가 느리게
    처리되는 동안에도 `/health`는 동시에 응답해야 한다. `_TEST_SLEEP_FOREVER`
    Hook은 `classify_format`으로 도달할 수 없으므로, `parse_document` 자체를
    Monkeypatch해 "느린 처리"를 흉내 낸다(Route가 실제로 Thread로 위임하는지
    검증하는 것이 목적이므로 Parser 내부 로직은 이 테스트의 관심사가
    아니다)."""
    release = threading.Event()
    started = threading.Event()
    original_parse_document = routes.parse_document

    def slow_parse_document(*args, **kwargs):
        started.set()
        release.wait(10)
        return original_parse_document(*args, **kwargs)

    routes.parse_document = slow_parse_document
    try:
        results = {}

        def call_parse():
            files = {"file": ("a.txt", b"hello", "text/plain")}
            results["parse"] = client.post("/parse", files=files, data={"declaredMimeType": "text/plain"})

        thread = threading.Thread(target=call_parse)
        thread.start()
        assert started.wait(5), "slow /parse never started"

        # /parse가 아직 release를 기다리며 "느리게 처리 중"인 동안에도
        # /health는 즉시 응답해야 한다 - Event Loop가 막혀 있지 않다는 증거다.
        health_started = time.monotonic()
        health_response = client.get("/health")
        health_elapsed = time.monotonic() - health_started

        assert health_response.status_code == 200
        assert health_elapsed < 2, "/health was blocked by the in-flight slow /parse"

        release.set()
        thread.join(10)
        assert results["parse"].status_code == 200
    finally:
        routes.parse_document = original_parse_document
        release.set()


def test_capacity_is_enforced_for_concurrent_parse_requests():
    """설정된 동시 처리 상한(`max_concurrent_parses`)을 넘는 요청은 대기시키지
    않고 즉시 busy로 응답해야 한다 - `parser_service._semaphore`가 실제로 이
    Topology(이 Service Instance 안의 모든 요청이 공유하는 하나의
    Semaphore)에서 강제한다는 것을 HTTP 경로를 통해 확인한다. 유일한 Slot을
    Semaphore 자체를 직접 점유해 결정적으로 재현한다(routes.parse_document를
    감싸는 방식은 실제 Semaphore 점유 시점과 인위적 지연 시점이 어긋나 Race가
    생긴다 - 이 방식이 더 직접적이고 결정적이다)."""
    original = routes.get_settings
    ps._concurrency_semaphore = None
    try:
        limited_settings = Settings(max_concurrent_parses=1, parse_timeout_seconds=5)
        routes.get_settings = lambda: limited_settings

        semaphore = ps._semaphore(limited_settings)
        assert semaphore.acquire(blocking=False) is True
        try:
            files = {"file": ("b.txt", b"hello", "text/plain")}
            response = client.post("/parse", files=files, data={"declaredMimeType": "text/plain"})

            assert response.status_code == 200
            body = response.json()
            assert body["outcome"] == "FAILED"
            assert body["reason"] == "server at capacity"
        finally:
            semaphore.release()
    finally:
        routes.get_settings = original
        ps._concurrency_semaphore = None
