"""M11 신규(F-AI-index) - Local Embedding Provider Adapter(CLAUDE.md "Local
LLM(Ollama)을 기본 Provider로 사용한다"). Canonical 결정(V002/CLAUDE.md "Vector
결정")인 ``bge-m3:567m``/1024차원/Cosine만 사용한다 - 이 값들은 새 Migration +
사용자 승인 없이는 바꾸지 않는다.

이 Adapter는 실제 Ollama HTTP API(``/api/embed``)를 호출하는 진짜 구현이다 -
Production Placeholder Vector를 반환하지 않는다("Implement a real configurable
local embedding adapter, not production placeholder vectors"). Model을 미리
내려받으라고 요청(``/api/pull`` 등)하지 않는다 - Provider가 이미 준비돼 있지
않으면(모델 없음/연결 불가) 그대로 실패로 보고한다("no automatic model
downloads").

응답 검증은 여기서 끝낸다 - 개수/차원/유한(non-NaN/Inf) 값을 확인하지 못하면
``None``을 반환해 호출자가 FAILED로 처리하게 한다(Malformed 응답이 절대
INDEXED로 이어지지 않는다).
"""

from __future__ import annotations

import math
from typing import List, Optional

import httpx

from app.core.config import Settings

# V002/CLAUDE.md "Vector 결정 (Canonical)" - 변경 시 새 Migration + 사용자 승인 필요.
EMBEDDING_MODEL = "bge-m3:567m"
EMBEDDING_DIMENSIONS = 1024


def embed_texts(texts: List[str], settings: Settings) -> Optional[List[List[float]]]:
    """``texts`` 순서 그대로 Embedding Vector 목록을 반환한다. 어떤 이유로든
    신뢰할 수 없는 결과면(Provider 접속 실패, 개수 불일치, 차원 불일치, 비유한
    값 등) 부분 결과를 조합하지 않고 ``None``을 반환한다."""

    if not texts:
        return None
    try:
        response = httpx.post(
            f"{settings.ollama_base_url.rstrip('/')}/api/embed",
            json={"model": EMBEDDING_MODEL, "input": texts},
            timeout=settings.ollama_embed_timeout_seconds,
        )
        response.raise_for_status()
        body = response.json()
    except Exception:
        # Provider 접속 정보/원본 예외 메시지를 로그/응답에 옮기지 않는다.
        return None

    embeddings = body.get("embeddings") if isinstance(body, dict) else None
    if not isinstance(embeddings, list) or len(embeddings) != len(texts):
        return None

    validated: List[List[float]] = []
    for vector in embeddings:
        if not isinstance(vector, list) or len(vector) != EMBEDDING_DIMENSIONS:
            return None
        values: List[float] = []
        for value in vector:
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                return None
            numeric = float(value)
            if not math.isfinite(numeric):
                return None
            values.append(numeric)
        validated.append(values)
    return validated
