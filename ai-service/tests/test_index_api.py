"""M11 (F-AI-index) - {@code /index} API 계약 검증(FastAPI TestClient, In-Process).
평문 Chunk Text/정규화된 전체 Text가 응답에 전혀 없음을 직접 확인한다."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.api import routes
from app.core.config import Settings
from app.main import app
from app.services import embedding_service

client = TestClient(app)


def _settings_with_hmac_key(**overrides) -> Settings:
    base = dict(index_content_hmac_key="synthetic-test-hmac-key", chunk_max_chars=1000, chunk_overlap_chars=0,
            max_chunks_per_document=50)
    base.update(overrides)
    return Settings(**base)


def _fake_embeddings(texts):
    return [[0.1] * 1024 for _ in texts]


def test_index_returns_vectors_and_locators_without_any_plaintext_for_a_successful_document(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings_with_hmac_key())
    monkeypatch.setattr(embedding_service, "embed_texts", lambda texts, settings: _fake_embeddings(texts))
    files = {"file": ("notes.txt", b"hello index world, this is a synthetic canary sentence.", "text/plain")}

    response = client.post("/index", files=files, data={"declaredMimeType": "text/plain"})

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "SUCCESS"
    assert body["embeddingModel"] == "bge-m3:567m"
    assert body["embeddingDimensions"] == 1024
    assert len(body["chunks"]) >= 1
    for chunk in body["chunks"]:
        assert len(chunk["embedding"]) == 1024
        assert len(chunk["contentHmac"]) == 64
        # 응답 JSON 전체에 원본 텍스트/평문 Chunk 자체가 없어야 한다 - 오직 위
        # 필드(embedding/contentHmac/locator)만 있고 "text"/"normalizedText" 키가 없다.
        assert set(chunk.keys()) == {"chunkIndex", "locatorType", "locatorValue", "embedding", "contentHmac"}
    assert "normalizedText" not in body
    assert "hello index world" not in response.text


def test_index_skips_chunking_and_embedding_for_an_unsupported_format(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings_with_hmac_key())
    embed_calls = []
    monkeypatch.setattr(embedding_service, "embed_texts", lambda texts, settings: embed_calls.append(texts))
    files = {"file": ("legacy.doc", b"whatever bytes", "application/msword")}

    response = client.post("/index", files=files, data={"declaredMimeType": "application/msword"})

    assert response.status_code == 200
    assert response.json()["outcome"] == "UNSUPPORTED_FORMAT"
    assert embed_calls == []  # Embedding Provider를 호출조차 하지 않았다 - 불필요한 외부 호출 없음.


def test_index_fails_closed_when_the_content_hmac_key_is_not_configured(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings_with_hmac_key(index_content_hmac_key=None))
    files = {"file": ("notes.txt", b"some content", "text/plain")}

    response = client.post("/index", files=files, data={"declaredMimeType": "text/plain"})

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "FAILED"
    assert "hmac" in (body["reason"] or "").lower()


def test_index_fails_closed_when_the_embedding_provider_returns_a_malformed_response(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings_with_hmac_key())
    monkeypatch.setattr(embedding_service, "embed_texts", lambda texts, settings: None)
    files = {"file": ("notes.txt", b"some content that will be chunked", "text/plain")}

    response = client.post("/index", files=files, data={"declaredMimeType": "text/plain"})

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "FAILED"
    assert body["chunks"] is None
