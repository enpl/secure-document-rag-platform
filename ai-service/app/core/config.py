"""F-AI-008. AI Service settings.

M06 Content Processing 한도 - 전부 환경변수로 재정의 가능한 "제안된 구현
기본값"이며, v3.2 명세가 확정한 값이 아니다(M06 구현 승인 계약서 참고).
운영 값을 여기서 확정하지 않는다 - 필요하면 배포 환경(Docker Compose 등)의
환경변수로 덮어쓴다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Optional


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


def _str_env(name: str, default: str) -> str:
    raw = os.getenv(name)
    return default if raw is None or raw.strip() == "" else raw


def _optional_str_env(name: str) -> Optional[str]:
    raw = os.getenv(name)
    return raw if raw and raw.strip() != "" else None


@dataclass(frozen=True)
class Settings:
    # 원본 입력 Byte 상한.
    max_input_bytes: int = 25 * 1024 * 1024

    # Zip 기반 Container(DOCX/XLSX)의 압축 해제 후 총 Byte 상한.
    max_decompressed_bytes: int = 200 * 1024 * 1024

    # 압축 해제 비율 상한(신장 비율) - 이 값을 넘으면 Zip Bomb으로 간주한다.
    # M06 최초 설계는 10을 제안했으나, 실측 결과 문단 하나뿐인 최소 실제
    # DOCX조차 Word 자체의 기본 styles.xml/stylesWithEffects.xml
    # Boilerplate 때문에 약 24:1 집계 비율을 갖는다(parser_service.py의
    # validate_zip_container Docstring 참고) - 10:1은 정상 DOCX를 전부
    # 거부시킨다. 100으로 재조정했다: 실제 Zip Bomb(수백~수만 배)은 여전히
    # 크게 넘는 값이다.
    max_compression_ratio: int = 100

    # Container 내부 Entry(파일) 개수 상한.
    max_container_entries: int = 1000

    # PDF 페이지 수 상한.
    max_pdf_pages: int = 500

    # XLSX 시트 수 상한.
    max_xlsx_sheets: int = 50

    # XLSX 시트당 셀 수 상한.
    max_xlsx_cells_per_sheet: int = 100_000

    # 정규화된 출력 텍스트 길이(문자 수) 상한 - 초과하면 성공이 아니라 실패다
    # (잘린 텍스트를 성공으로 포장하지 않는다).
    max_output_chars: int = 5_000_000

    # 문서 하나를 Parsing하는 데 허용하는 실제 실행 시간(초) - 이 시간을
    # 넘기면 실제로 그 작업을 수행 중인 별도 Process를 종료시킨다(HTTP 대기만
    # 끊는 것으로는 부족하다).
    parse_timeout_seconds: int = 60

    # 이 Service Instance 안에서 동시에 활성화할 수 있는 Parser 작업 수 상한.
    max_concurrent_parses: int = 4

    # M11 - /index Chunking 한도(제안된 구현 기본값 - v3.2 명세가 확정한 값이 아니다).
    # Chunk 하나의 최대 문자 수.
    chunk_max_chars: int = 1800
    # 인접 Chunk 사이 겹침 문자 수(경계 근처 문맥 유실 완화) - chunk_max_chars보다 작아야 한다.
    chunk_overlap_chars: int = 200
    # 문서 하나가 만들 수 있는 최대 Chunk 개수 - 넘으면 잘라내지 않고 FAILED로 실패한다
    # (parser_service.py의 출력 길이 상한과 동일한 "잘린 결과를 성공으로 포장하지 않는다" 원칙).
    max_chunks_per_document: int = 200

    # M11 - Local Embedding Provider(Ollama, CLAUDE.md "Local LLM(Ollama)을 기본
    # Provider로 사용한다"). 이미 Backend(application-*.yml)가 쓰는 것과 같은 환경변수
    # 이름을 재사용한다(OLLAMA_BASE_URL, .env.example에 이미 존재) - 새 이름을 만들지 않는다.
    ollama_base_url: str = "http://localhost:11434"
    ollama_embed_timeout_seconds: int = 60

    # M11 - Content HMAC Key. 외부에서 주입되어야 한다(운영자 Secret) - 하드코딩된
    # 운영 Key/Unkeyed Hash 대체/조용한 미보안 기본값을 두지 않는다. None이면 /index가
    # 이 Key가 실제로 필요해지는 시점(성공적인 Chunking 이후, HMAC 계산 직전)에만
    # FAILED로 안전하게 거부한다(Application 기동 자체는 막지 않는다 - Backend의
    # token-encryption-key와 동일한 "실제 사용 시점에만 Fail Closed" 관례).
    index_content_hmac_key: Optional[str] = None


def get_settings() -> Settings:
    return Settings(
        max_input_bytes=_int_env("SDV_PARSE_MAX_INPUT_BYTES", Settings.max_input_bytes),
        max_decompressed_bytes=_int_env("SDV_PARSE_MAX_DECOMPRESSED_BYTES", Settings.max_decompressed_bytes),
        max_compression_ratio=_int_env("SDV_PARSE_MAX_COMPRESSION_RATIO", Settings.max_compression_ratio),
        max_container_entries=_int_env("SDV_PARSE_MAX_CONTAINER_ENTRIES", Settings.max_container_entries),
        max_pdf_pages=_int_env("SDV_PARSE_MAX_PDF_PAGES", Settings.max_pdf_pages),
        max_xlsx_sheets=_int_env("SDV_PARSE_MAX_XLSX_SHEETS", Settings.max_xlsx_sheets),
        max_xlsx_cells_per_sheet=_int_env("SDV_PARSE_MAX_XLSX_CELLS_PER_SHEET", Settings.max_xlsx_cells_per_sheet),
        max_output_chars=_int_env("SDV_PARSE_MAX_OUTPUT_CHARS", Settings.max_output_chars),
        parse_timeout_seconds=_int_env("SDV_PARSE_TIMEOUT_SECONDS", Settings.parse_timeout_seconds),
        max_concurrent_parses=_int_env("SDV_PARSE_MAX_CONCURRENT", Settings.max_concurrent_parses),
        chunk_max_chars=_int_env("SDV_INDEX_CHUNK_MAX_CHARS", Settings.chunk_max_chars),
        chunk_overlap_chars=_int_env("SDV_INDEX_CHUNK_OVERLAP_CHARS", Settings.chunk_overlap_chars),
        max_chunks_per_document=_int_env("SDV_INDEX_MAX_CHUNKS", Settings.max_chunks_per_document),
        ollama_base_url=_str_env("OLLAMA_BASE_URL", Settings.ollama_base_url),
        ollama_embed_timeout_seconds=_int_env("SDV_INDEX_OLLAMA_TIMEOUT_SECONDS",
                Settings.ollama_embed_timeout_seconds),
        index_content_hmac_key=_optional_str_env("SDV_INDEX_CONTENT_HMAC_KEY"),
    )
