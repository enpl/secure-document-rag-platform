"""M12 신규(F-AI-embed-query) - ``POST /embed-query`` API 계약 검증. 실제 Ollama
Server를 띄우지 않는다 - httpx.post를 결정론적으로 Monkeypatch한다(test_embedding.py와
동일한 관례)."""

from __future__ import annotations

import httpx
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        pass

    def json(self):
        return self._payload


def test_embed_query_returns_a_1024_dimensional_vector_for_well_formed_text(monkeypatch):
    fake_embedding = [0.25] * 1024
    monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResponse({"embeddings": [fake_embedding]}))

    response = client.post("/embed-query", json={"text": "find the security policy"})

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "SUCCESS"
    assert body["embeddingDimensions"] == 1024
    assert len(body["embedding"]) == 1024
    assert body["embeddingModel"] == "bge-m3:567m"


def test_embed_query_fails_closed_when_the_provider_is_unreachable(monkeypatch):
    def _raise(*args, **kwargs):
        raise httpx.ConnectError("connection refused", request=None)

    monkeypatch.setattr(httpx, "post", _raise)

    response = client.post("/embed-query", json={"text": "find the security policy"})

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "FAILED"
    assert body["embedding"] is None


def test_embed_query_rejects_blank_text_without_calling_the_provider(monkeypatch):
    called = {"value": False}

    def _fail_if_called(*args, **kwargs):
        called["value"] = True
        raise AssertionError("embedding provider must not be called for blank text")

    monkeypatch.setattr(httpx, "post", _fail_if_called)

    response = client.post("/embed-query", json={"text": "   "})

    assert response.status_code == 200
    assert response.json()["outcome"] == "FAILED"
    assert called["value"] is False


def test_embed_query_rejects_text_over_the_configured_length_limit(monkeypatch):
    called = {"value": False}

    def _fail_if_called(*args, **kwargs):
        called["value"] = True
        raise AssertionError("embedding provider must not be called for over-limit text")

    monkeypatch.setattr(httpx, "post", _fail_if_called)

    response = client.post("/embed-query", json={"text": "x" * 3000})

    assert response.status_code == 200
    assert response.json()["outcome"] == "FAILED"
    assert called["value"] is False
