"""F-AI-002. Backend 전용 내부 API - {@code /parse}, {@code /health}.

M06은 {@code /parse}/{@code /health}만 구현한다({@code /index}는 M11+ 범위).
공개 인증되지 않은 배포를 전제하지 않는다 - 고정된 운영자 설정 내부
주소로만 호출된다({@code sdv.ai-service.url}, Backend 쪽 설정).
"""

from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException, Request
from starlette.datastructures import UploadFile as StarletteUploadFile

from app.core.config import get_settings
from app.models.schemas import HealthResponseDto, ParseResponseDto
from app.services.parser_service import parse_document

router = APIRouter()

# multipart Boundary/Header/declaredMimeType 필드 등 Envelope Overhead를 위한
# 여유분 - 실제 File 내용 자체의 한도(settings.max_input_bytes)와는 별개다.
_MULTIPART_ENVELOPE_OVERHEAD_BYTES = 64 * 1024


@router.get("/health", response_model=HealthResponseDto)
def health() -> HealthResponseDto:
    return HealthResponseDto(status="OK")


@router.post("/parse", response_model=ParseResponseDto)
async def parse(request: Request) -> ParseResponseDto:
    """FastAPI의 표준 {@code UploadFile = File(...)} 자동 주입을 의도적으로
    쓰지 않는다 - 그 경로는 Handler가 실행되기 전에 Starlette가 이미 전체
    Multipart Body를 Parsing/Spool까지 마친 뒤이므로, 그 이후에 아무리
    "누적 크기를 검사"해도 "Network Body를 이미 다 받은 뒤"의 검사일 뿐이다
    (M06 후속 교정 - 이전 구현의 실제 결함). 대신 원시 ASGI Byte Stream을
    직접 읽으면서(Content-Length를 신뢰하지 않는다 - 없거나 Chunked
    Transfer라도 실제 수신 Byte만 센다) 누적 크기가 한도를 넘는 즉시
    중단한다 - 그 이후에만 (이미 한도 안으로 확정된) Body를 Multipart로
    해석한다.
    """
    settings = get_settings()
    bounded_body = await _read_bounded_body(request, settings.max_input_bytes + _MULTIPART_ENVELOPE_OVERHEAD_BYTES)
    bounded_request = _request_with_body(request, bounded_body)
    form = await bounded_request.form(max_part_size=settings.max_input_bytes + _MULTIPART_ENVELOPE_OVERHEAD_BYTES)
    try:
        file = form.get("file")
        if not isinstance(file, StarletteUploadFile):
            raise HTTPException(status_code=400, detail="file part is required")
        declared_mime_type = form.get("declaredMimeType") or ""
        content = await file.read()
    finally:
        await form.close()

    # 실제 Parsing(Multiprocessing Timeout/Semaphore 포함)은 Blocking 작업이다
    # - Event Loop를 막지 않도록 별도 Thread에서 실행한다(그래야 이 요청이
    # 느리게 처리되는 동안에도 /health 등 다른 요청이 계속 응답할 수 있다).
    return await asyncio.to_thread(parse_document, content, file.filename or "", declared_mime_type or None,
            settings)


async def _read_bounded_body(request: Request, max_bytes: int) -> bytes:
    """원시 ASGI Byte Stream을 직접 소비하면서 누적 크기가 한도를 넘는 즉시
    중단한다 - Starlette의 Multipart/Form Parsing이 시작되기 전이므로,
    한도 초과 입력은 전체가 Buffer/Disk에 Spool되지 않는다."""
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(status_code=413, detail="request body exceeds limit")
        chunks.append(chunk)
    return b"".join(chunks)


def _request_with_body(original: Request, body: bytes) -> Request:
    """이미 한도 안으로 확정된 Body로 새 Request를 만든다 - 원본 Request의
    Stream은 위에서 이미 다 소비했으므로, 이후 `.form()` 호출이 그 Stream을
    다시 읽으려 하지 않도록 새 (이미 완료된) receive Callable을 준다."""

    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    return Request(original.scope, receive)
