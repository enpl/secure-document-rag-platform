"""F-AI-009. parser_service.py 검증 - Postgres/HTTP 없이 순수 Python 단위
테스트다(Backend Testcontainers 테스트와 독립적으로 빠르게 실행된다)."""

from __future__ import annotations

import io
import multiprocessing as mp
import tempfile
import threading
import time
import zipfile
from pathlib import Path

import docx
import openpyxl
import pytest

from app.core.config import Settings
from app.models.schemas import (
    OUTCOME_FAILED,
    OUTCOME_NO_TEXT,
    OUTCOME_SUCCESS,
    OUTCOME_UNSUPPORTED_FORMAT,
)
from app.services import parser_service as ps

DEFAULT_SETTINGS = Settings()


def _small_settings(**overrides) -> Settings:
    return Settings(**{**{}, **overrides})


# ---------------------------------------------------------------------------
# Fixtures - 실제 설치된 Library로 직접 만든 유효한 문서(외부 샘플 파일 불필요).
# ---------------------------------------------------------------------------
def _make_docx(paragraphs: list[str]) -> bytes:
    document = docx.Document()
    for text in paragraphs:
        document.add_paragraph(text)
    buffer = io.BytesIO()
    document.save(buffer)
    return buffer.getvalue()


def _make_xlsx(sheets: dict[str, list[list]]) -> bytes:
    workbook = openpyxl.Workbook()
    workbook.remove(workbook.active)
    for name, rows in sheets.items():
        sheet = workbook.create_sheet(name)
        for row in rows:
            sheet.append(row)
    buffer = io.BytesIO()
    workbook.save(buffer)
    return buffer.getvalue()


_MINIMAL_PDF = (
    b"%PDF-1.4\n"
    b"1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/Resources<</Font<</F1 4 0 R>>>>"
    b"/MediaBox[0 0 200 200]/Contents 5 0 R>>endobj\n"
    b"4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
    b"5 0 obj<</Length 58>>\nstream\n"
    b"BT /F1 24 Tf 20 100 Td (Hello PDF World) Tj ET\n"
    b"endstream\nendobj\n"
    b"trailer<</Size 6/Root 1 0 R>>\n"
    b"%%EOF"
)


# ---------------------------------------------------------------------------
# 지원 대표 포맷
# ---------------------------------------------------------------------------
def test_plain_text_success():
    content = "hello world\nline two".encode("utf-8")
    result = ps.parse_document(content, "notes.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert result.normalizedText == "hello world\nline two"
    assert result.parserName == "charset-normalizer"
    assert result.locations[0].locatorType == "DOCUMENT"
    assert result.locations[0].startOffset == 0
    assert result.locations[0].endOffset == len(result.normalizedText)


def test_pdf_success_with_page_location():
    result = ps.parse_document(_MINIMAL_PDF, "sample.pdf", "application/pdf", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert "Hello PDF World" in result.normalizedText
    assert result.parserName == "pdfminer.six"
    assert any(loc.locatorType == "PAGE" and loc.locatorValue == "1" for loc in result.locations)


def test_docx_success():
    content = _make_docx(["First paragraph.", "Second paragraph."])
    result = ps.parse_document(content, "report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert "First paragraph." in result.normalizedText
    assert "Second paragraph." in result.normalizedText
    assert result.parserName == "python-docx"
    assert result.locations[0].locatorType == "DOCUMENT"


def test_xlsx_success_with_sheet_range_location():
    content = _make_xlsx({"Sheet1": [["a", "b"], [1, 2]]})
    result = ps.parse_document(content, "data.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert "a" in result.normalizedText
    assert result.parserName == "openpyxl"
    assert any(loc.locatorType == "SHEET_RANGE" and loc.locatorValue == "Sheet1" for loc in result.locations)


def test_docx_table_text_is_extracted_in_document_order_including_important_facts():
    # 문단만 훑으면(document.paragraphs) 표 내용이 조용히 빠진다 - 표만 있는
    # 문서는 NO_TEXT로 잘못 분류될 수도 있다. 중요한 사실이 표 셀 안에만
    # 있는 Fixture로 실제로 뽑히는지 확인한다.
    document = docx.Document()
    document.add_paragraph("Heading text")
    table = document.add_table(rows=1, cols=2)
    table.rows[0].cells[0].text = "the secret budget is 4200 dollars"
    table.rows[0].cells[1].text = "second cell"
    document.add_paragraph("After table")
    buffer = io.BytesIO()
    document.save(buffer)
    content = buffer.getvalue()

    result = ps.parse_document(content, "with-table.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert "Heading text" in result.normalizedText
    assert "the secret budget is 4200 dollars" in result.normalizedText
    assert "After table" in result.normalizedText
    # 문서 순서: 표 앞 문단이 표 셀 내용보다 먼저 나온다.
    assert result.normalizedText.index("Heading text") < result.normalizedText.index("4200 dollars")


def test_docx_table_only_document_is_not_falsely_no_text():
    document = docx.Document()
    table = document.add_table(rows=1, cols=1)
    table.rows[0].cells[0].text = "only a table cell"
    buffer = io.BytesIO()
    document.save(buffer)
    content = buffer.getvalue()

    result = ps.parse_document(content, "table-only.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert "only a table cell" in result.normalizedText


def test_xlsx_offsets_are_correct_with_empty_first_sheet_and_multiple_sheets():
    # 첫 시트가 비어 있으면(과거 구현에서는) 빈 Segment가 그대로 이어붙여져
    # 나머지 Offset이 밀렸다 - 지금은 빈 Segment를 join 대상에서 아예
    # 제외하므로 이런 밀림이 없어야 한다.
    content = _make_xlsx({"Empty": [], "Data": [["only", "here"]]})
    result = ps.parse_document(content, "empty-first.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    text = result.normalizedText
    # 빈 시트로 인한 선행 공백/개행이 전혀 없어야 한다.
    assert text == text.lstrip()
    location = next(loc for loc in result.locations if loc.locatorValue == "Data")
    _assert_location_matches_utf16_span(text, location)


def test_xlsx_offsets_remain_correct_across_multiple_non_empty_sheets_with_non_bmp_text():
    content = _make_xlsx({"S1": [["a😀b"]], "S2": [["plain"]]})
    result = ps.parse_document(content, "multi-sheet.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    text = result.normalizedText
    for location in result.locations:
        _assert_location_matches_utf16_span(text, location)


def _assert_location_matches_utf16_span(text: str, location) -> None:
    """Offset이 실제로 `text`의 UTF-16 code unit 길이 안에 들어오고,
    start <= end인지 검증한다 - Java 쪽에서 그대로 신뢰할 수 있는 최소
    불변식이다."""
    utf16_length = len(text.encode("utf-16-le")) // 2
    assert 0 <= location.startOffset <= location.endOffset <= utf16_length


# ---------------------------------------------------------------------------
# Unicode / non-BMP 위치 매핑 (Java UTF-16 code unit 단위)
# ---------------------------------------------------------------------------
def test_non_bmp_character_offsets_use_utf16_code_units():
    # U+1F600 (😀)는 Non-BMP - Java String에서는 Surrogate Pair 2개(UTF-16 code
    # unit 2개)로 계산되지만 Python str에서는 코드포인트 1개다.
    text = "a😀b"
    content = text.encode("utf-8")
    result = ps.parse_document(content, "emoji.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert result.normalizedText == text
    location = result.locations[0]
    assert location.startOffset == 0
    # "a"(1) + Surrogate Pair(2) + "b"(1) = UTF-16 code unit 길이 4,
    # Python 코드포인트 길이(3)와 다르다는 것이 이 테스트의 핵심이다.
    assert location.endOffset == 4
    assert len(text) == 3


# ---------------------------------------------------------------------------
# 미지원 / 텍스트 없음
# ---------------------------------------------------------------------------
def test_unsupported_extension_is_rejected():
    result = ps.parse_document(b"whatever", "legacy.doc", "application/msword", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_UNSUPPORTED_FORMAT


def test_empty_input_is_no_text():
    result = ps.parse_document(b"", "empty.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_NO_TEXT


def test_whitespace_only_text_is_no_text():
    result = ps.parse_document(b"   \n\t  ", "blank.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_NO_TEXT


# ---------------------------------------------------------------------------
# Spoofing - 확장자/선언된 MIME/실제 Byte가 서로 다름
# ---------------------------------------------------------------------------
def test_real_pdf_renamed_with_txt_extension_is_rejected():
    result = ps.parse_document(_MINIMAL_PDF, "disguised.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_UNSUPPORTED_FORMAT


def test_declared_mime_contradicting_detected_content_is_rejected():
    # 확장자와 실제 Byte는 PDF로 일치하지만, 선언된 MIME이 DOCX를 주장한다.
    result = ps.parse_document(_MINIMAL_PDF, "sample.pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_UNSUPPORTED_FORMAT


def test_encrypted_office_container_is_rejected_without_attempting_zip_open():
    # 암호화된 OOXML은 일반 Zip이 아니라 CFBF(OLE) 형식이다(Magic Byte가 다르다) -
    # 이 사실만으로 감지되어(zipfile을 시도조차 하지 않고) 거부된다.
    cfbf_header = bytes.fromhex("D0CF11E0A1B11AE1") + b"\x00" * 32
    result = ps.parse_document(cfbf_header, "protected.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_UNSUPPORTED_FORMAT


# ---------------------------------------------------------------------------
# Container 손상/악성
# ---------------------------------------------------------------------------
def test_corrupt_docx_bytes_fail_safely():
    valid = _make_docx(["hello"])
    corrupted = valid[: len(valid) // 2]
    result = ps.parse_document(corrupted, "broken.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome in (OUTCOME_FAILED, OUTCOME_UNSUPPORTED_FORMAT)


def test_path_traversal_entry_is_rejected():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("word/document.xml", "<w:document/>")
        zf.writestr("../evil.txt", "escaped")
    content = buffer.getvalue()

    result = ps.parse_document(content, "evil.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_FAILED
    assert "unsafe container" in (result.reason or "")


def test_zip_bomb_style_compression_ratio_is_rejected_at_the_default_ratio():
    # 실측: 문단 하나뿐인 최소 실제 DOCX조차 ~24:1 집계 비율을 갖는다
    # (parser_service.validate_zip_container Docstring 참고) - 그래서
    # 기본값은 10이 아니라 100이다. 이 Fixture(2MB의 0-byte)는 그보다 훨씬
    # 큰 ~1000:1이므로 기본 한도로도 여전히 잡혀야 한다(정상 문서를 막지
    # 않으면서 실제 Bomb은 여전히 막는다는 것을 함께 증명한다).
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("word/document.xml", "<w:document/>")
        zf.writestr("word/media/bomb.bin", b"\x00" * (2 * 1024 * 1024))
    content = buffer.getvalue()

    result = ps.parse_document(content, "bomb.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_FAILED
    assert "unsafe container" in (result.reason or "")


def test_many_small_highly_compressible_entries_trip_the_aggregate_ratio():
    # 개별 Entry 하나하나는(비율 검사 관점에서) 작지만, 합쳐서 보면 Bomb과
    # 같은 신장 비율이다 - 개별 Entry 검사만으로는 놓칠 수 있는 경우를
    # 집계(Aggregate) 검사가 잡는다는 것을 증명한다.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("word/document.xml", "<w:document/>")
        for i in range(50):
            zf.writestr(f"word/media/part{i}.bin", b"\x00" * (50 * 1024))
    content = buffer.getvalue()

    strict_settings = Settings(max_compression_ratio=100)
    result = ps.parse_document(content, "many-small-bombs.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", strict_settings)

    assert result.outcome == OUTCOME_FAILED
    assert "unsafe container" in (result.reason or "")


def test_declared_size_mismatch_is_rejected_by_the_actual_read_check():
    # Central Directory에 선언된 uncompressed size를 실제보다 작게 위조한
    # Archive - 선언된 크기'만' 믿었다면 통과했을 것이다. 실제로는 두 가지
    # 독립된 방어가 함께 이를 잡는다: (1) 우리가 직접 읽은 실제 Byte 수와
    # 선언된 크기를 대조하는 이 파일의 검사, (2) zipfile 자체가 압축 해제
    # 도중 CRC-32를 검증해(Local/Central Header의 size 필드가 실제
    # 압축 데이터와 맞지 않으면) BadZipFile을 던진다 - 이 테스트가 실제로
    # 관찰하는 즉시 경로는 (2)다(validate_zip_container가 이를 잡아
    # "malformed container"로 번역한다). 둘 다 "unsafe container"로 이어져
    # 같은 결과를 보장한다.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("word/document.xml", "<w:document/>")
        zf.writestr("word/settings.xml", "x" * 10_000)
    content = bytearray(buffer.getvalue())

    with zipfile.ZipFile(io.BytesIO(bytes(content))) as zf:
        target = next(i for i in zf.infolist() if i.filename == "word/settings.xml")
    # Central Directory Header 안의 uncompressed size(4-byte Little-Endian)
    # 필드를 실제보다 작은 값으로 덮어쓴다(zipfile의 표준 Local/Central
    # Header 배치를 이용한 결정적 변조 - 무작위 Byte 변경이 아니다).
    header_offset = content.rfind(b"PK\x01\x02")
    while header_offset != -1:
        name_len = int.from_bytes(content[header_offset + 28:header_offset + 30], "little")
        name = bytes(content[header_offset + 46:header_offset + 46 + name_len])
        if name == b"word/settings.xml":
            content[header_offset + 24:header_offset + 28] = (100).to_bytes(4, "little")
            break
        header_offset = content.rfind(b"PK\x01\x02", 0, header_offset)

    result = ps.parse_document(bytes(content), "forged.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_FAILED
    assert "unsafe container" in (result.reason or "")


def test_macro_enabled_container_is_rejected_even_when_renamed_to_docx():
    # "우리가 실행하지 않는다"는 실행하지 않는다는 것만 증명할 뿐, 거부를
    # 증명하지 않는다 - vbaProject.bin이 있으면 구조적으로는 유효한
    # word/document.xml을 가진 docx라도 적극적으로 거부해야 한다.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("word/document.xml", "<w:document/>")
        zf.writestr("word/vbaProject.bin", b"\x00\x01\x02macro-bytes")
    content = buffer.getvalue()

    result = ps.parse_document(content, "macro.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_FAILED
    assert "active content" in (result.reason or "")


def test_container_entry_count_limit_is_enforced():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("word/document.xml", "<w:document/>")
        for i in range(5):
            zf.writestr(f"extra/file{i}.txt", "x")
    content = buffer.getvalue()

    strict_settings = Settings(max_container_entries=3)
    result = ps.parse_document(content, "many.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", strict_settings)

    assert result.outcome == OUTCOME_FAILED
    assert "unsafe container" in (result.reason or "")


def test_xxe_payload_in_docx_xml_does_not_leak_external_content(tmp_path: Path):
    # 참조 대상이 존재하지 않으면(예: file:///nonexistent) XXE 방어가 전혀
    # 작동하지 않아도 당연히 그 내용이 안 보인다 - 그 자체로는 증거가
    # 아니다. 실제로 존재하고, 고유하고, 무해한 Marker 파일을 만들어
    # 참조하고, 그 Marker의 실제 내용이 결과에 나타나지 않는지 확인한다 -
    # 이것이 "외부 Entity가 실제로 해석되지 않았다"는 의미 있는 증거다.
    # 실제 Secret/외부 서비스는 절대 사용하지 않는다(고립된 임시 Fixture).
    marker_secret = "xxe-marker-" + str(id(object()))
    marker_file = tmp_path / "xxe_marker.txt"
    marker_file.write_text(marker_secret, encoding="utf-8")
    marker_uri = marker_file.as_uri()

    xxe_xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<!DOCTYPE root [<!ENTITY xxe SYSTEM "{marker_uri}">]>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        "<w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>"
    ).encode("utf-8")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr(
            "[Content_Types].xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            "</Types>",
        )
        zf.writestr(
            "_rels/.rels",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
            'Target="word/document.xml"/></Relationships>',
        )
        zf.writestr("word/document.xml", xxe_xml)
    content = buffer.getvalue()

    result = ps.parse_document(content, "xxe.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DEFAULT_SETTINGS)

    # 실제로 존재하고 읽을 수 있는 Marker 파일을 참조했는데도, 그 실제 내용이
    # 결과 어디에도(성공 텍스트든 실패 사유든) 나타나지 않는다 - python-docx가
    # 명시적으로 resolve_entities=False로 구성한 lxml Parser를 쓰기 때문이다
    # (docx.oxml.parser.oxml_parser, 이 저장소 .venv에서 직접 확인).
    text = result.normalizedText or ""
    reason = result.reason or ""
    assert marker_secret not in text
    assert marker_secret not in reason
    assert "&xxe;" not in text or result.outcome != OUTCOME_SUCCESS


# ---------------------------------------------------------------------------
# 크기/페이지/시트/셀/출력 한도
# ---------------------------------------------------------------------------
def test_input_size_limit_is_enforced():
    strict_settings = Settings(max_input_bytes=10)
    result = ps.parse_document(b"0123456789ABCDEF", "big.txt", "text/plain", strict_settings)

    assert result.outcome == OUTCOME_FAILED
    assert "input size" in (result.reason or "")


def test_output_length_limit_fails_rather_than_truncates():
    strict_settings = Settings(max_output_chars=5)
    result = ps.parse_document(b"this text is definitely longer than five chars", "long.txt", "text/plain",
            strict_settings)

    assert result.outcome == OUTCOME_FAILED
    assert result.normalizedText is None
    assert "output length" in (result.reason or "")


def test_xlsx_sheet_count_limit_is_enforced():
    content = _make_xlsx({"S1": [[1]], "S2": [[1]], "S3": [[1]]})
    strict_settings = Settings(max_xlsx_sheets=2)
    result = ps.parse_document(content, "many-sheets.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", strict_settings)

    assert result.outcome == OUTCOME_FAILED


def test_xlsx_cell_count_limit_is_enforced():
    content = _make_xlsx({"S1": [[1, 2, 3, 4, 5]]})
    strict_settings = Settings(max_xlsx_cells_per_sheet=3)
    result = ps.parse_document(content, "wide.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", strict_settings)

    assert result.outcome == OUTCOME_FAILED


def test_pdf_page_count_limit_is_enforced():
    strict_settings = Settings(max_pdf_pages=0)
    result = ps.parse_document(_MINIMAL_PDF, "sample.pdf", "application/pdf", strict_settings)

    assert result.outcome == OUTCOME_FAILED


# ---------------------------------------------------------------------------
# 실제 Timeout/Queue 전달 - M06 후속 교정(실제 결함 수정).
#
# 이전 구현은 process.join(timeout)을 먼저 호출한 뒤에야 Queue를 읽었다 -
# 결과가 OS Pipe Buffer보다 크면 자식이 다 쓰기를 기다리는 동안 Parent는
# join()으로 자식이 끝나기를 기다려 서로 교착했다(느린 Parsing이 아니라
# 이 패턴 때문에 큰 정상 결과가 거짓 Timeout으로 보였다). 아래 테스트는
# 실제 100KB/1MB 결과가 Machine의 Pipe Buffer 크기에 의존하지 않고
# 안정적으로 전달되는지 검증한다.
# ---------------------------------------------------------------------------
def test_small_valid_text_result_is_not_mistaken_for_a_timeout():
    content = ("x" * 1_000).encode("utf-8")
    result = ps.parse_document(content, "small.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert len(result.normalizedText) == 1_000


def test_large_valid_text_result_100kb_is_not_mistaken_for_a_timeout():
    content = ("y" * 100_000).encode("utf-8")
    result = ps.parse_document(content, "hundred-kb.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert len(result.normalizedText) == 100_000


def test_large_valid_text_result_1mb_is_not_mistaken_for_a_timeout():
    # 1MB는 전형적인 OS Pipe Buffer(수십~수백 KB)보다 명백히 크다 - 이 크기가
    # 안정적으로 왕복하는지가 Deadlock 수정의 핵심 증거다.
    content = ("z" * 1_000_000).encode("utf-8")
    result = ps.parse_document(content, "one-mb.txt", "text/plain", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_SUCCESS
    assert len(result.normalizedText) == 1_000_000


def test_child_process_failure_is_translated_to_failed_without_leaking_the_exception():
    settings = Settings(parse_timeout_seconds=5)

    result = ps._run_with_timeout(b"x", "whatever", None, settings, family_override="_TEST_FAIL_IMMEDIATELY")

    assert result.outcome == OUTCOME_FAILED
    assert result.reason == "parse error"
    assert "deliberate test failure" not in (result.reason or "")


def test_timeout_terminates_the_actual_worker_process_and_reaps_it():
    settings = Settings(parse_timeout_seconds=1)
    children_before = set(mp.active_children())
    started = time.monotonic()

    with pytest.raises(TimeoutError):
        ps._run_with_timeout(b"x", "whatever", None, settings, family_override="_TEST_SLEEP_FOREVER")

    elapsed = time.monotonic() - started
    # 실제로 10000초를 기다리지 않고(hang하지 않고) Timeout 근처(수 초 이내)에
    # 강제 종료되어 반환됐다는 것이 자원 회수 증거다.
    assert elapsed < 15
    # 강제 종료된 Process가 실제로 Reap되어 더 이상 살아있는 Child로 남지
    # 않는다(queue.empty()가 아니라 실제 Process 생존 여부로 검증한다).
    children_after = set(mp.active_children()) - children_before
    assert children_after == set()


# ---------------------------------------------------------------------------
# Bounded Admission - 한도 도달 시 대기시키지 않고 즉시 실패한다.
#
# `_semaphore()`는 Module 전역에 한 번만 생성되는 Singleton이다(운영에서는
# 의도적 - 설정이 한 Process 생애 동안 바뀌지 않는다고 가정한다) - 그래서
# 이 테스트는 자신이 원하는 크기로 재생성되도록 먼저 전역 상태를 리셋하고,
# 끝나면 다시 리셋해 다른 테스트에 이 테스트의 설정(max_concurrent_parses=1)이
# 새어나가지 않게 한다.
# ---------------------------------------------------------------------------
def test_capacity_exhaustion_fails_fast_without_queueing():
    settings = Settings(max_concurrent_parses=1, parse_timeout_seconds=5)
    ps._concurrency_semaphore = None
    try:
        semaphore = ps._semaphore(settings)
        assert semaphore.acquire(blocking=False) is True
        try:
            result = ps.parse_document(b"hello", "a.txt", "text/plain", settings)
            assert result.outcome == OUTCOME_FAILED
            assert result.reason == "server at capacity"
        finally:
            semaphore.release()
    finally:
        ps._concurrency_semaphore = None


# ---------------------------------------------------------------------------
# Classification/Container 검사도 Worker Timeout/Admission 경계 안에서
# 수행된다(M06 후속 교정) - family_override 없이, 실제 미지원 확장자로도
# 자식 Process를 거쳐 정상 분류되는지 재확인한다.
# ---------------------------------------------------------------------------
def test_classification_of_unsupported_format_still_goes_through_the_bounded_worker():
    result = ps.parse_document(b"whatever", "legacy.doc", "application/msword", DEFAULT_SETTINGS)

    assert result.outcome == OUTCOME_UNSUPPORTED_FORMAT
