"""F-AI-008. AI Service settings.

M06 Content Processing 한도 - 전부 환경변수로 재정의 가능한 "제안된 구현
기본값"이며, v3.2 명세가 확정한 값이 아니다(M06 구현 승인 계약서 참고).
운영 값을 여기서 확정하지 않는다 - 필요하면 배포 환경(Docker Compose 등)의
환경변수로 덮어쓴다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


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
    )
