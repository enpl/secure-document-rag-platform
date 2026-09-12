"""F-AI-001. FastAPI 진입점.

내부 전용 Service다 - 공개 인증되지 않은 배포를 전제하지 않는다(Docker
Compose 내부 네트워크에서 Backend만 호출한다, {@code sdv.ai-service.url}).
"""

from __future__ import annotations

from fastapi import FastAPI

from app.api.routes import router

app = FastAPI(title="SDV AI Service", version="0.1.0")
app.include_router(router)
