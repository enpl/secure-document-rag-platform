"""M11 (F-AI-index) - embedding_service의 Ollama Adapter 응답 검증(개수/차원/유한
값). 실제 Ollama Server를 띄우지 않는다 - httpx.post를 결정론적으로 Monkeypatch한다."""

from __future__ import annotations

import httpx

from app.core.config import Settings
from app.services import embedding_service


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        pass

    def json(self):
        return self._payload


def _settings() -> Settings:
    return Settings(ollama_base_url="http://fake-ollama:11434")


def test_embed_texts_returns_valid_1024_dimensional_vectors_for_a_well_formed_response(monkeypatch):
    texts = ["chunk one", "chunk two"]
    fake_embeddings = [[0.1] * 1024, [0.2] * 1024]
    monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResponse({"embeddings": fake_embeddings}))

    result = embedding_service.embed_texts(texts, _settings())

    assert result is not None
    assert len(result) == 2
    assert all(len(vector) == 1024 for vector in result)


def test_embed_texts_returns_none_when_the_provider_is_unreachable(monkeypatch):
    def _raise(*args, **kwargs):
        raise httpx.ConnectError("connection refused", request=None)

    monkeypatch.setattr(httpx, "post", _raise)

    result = embedding_service.embed_texts(["a chunk"], _settings())

    assert result is None


def test_embed_texts_returns_none_on_vector_count_mismatch(monkeypatch):
    monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResponse({"embeddings": [[0.1] * 1024]}))

    result = embedding_service.embed_texts(["chunk one", "chunk two"], _settings())

    assert result is None


def test_embed_texts_returns_none_when_dimensions_are_wrong(monkeypatch):
    monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResponse({"embeddings": [[0.1] * 512]}))

    result = embedding_service.embed_texts(["one chunk"], _settings())

    assert result is None


def test_embed_texts_returns_none_when_a_value_is_not_finite(monkeypatch):
    vector = [0.1] * 1023 + [float("nan")]
    monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResponse({"embeddings": [vector]}))

    result = embedding_service.embed_texts(["one chunk"], _settings())

    assert result is None


def test_embed_texts_returns_none_for_an_empty_input_list():
    result = embedding_service.embed_texts([], _settings())

    assert result is None
