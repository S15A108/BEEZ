import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.routers import document_chunks, embeddings, health, impact
from app.services.retrieval.tokenization import EmbeddingTokenCounter


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Fail before readiness if provisioning was skipped. No download or model
    # request occurs here, and tokenizer construction stays off the event loop.
    await asyncio.to_thread(EmbeddingTokenCounter)
    try:
        yield
    finally:
        provider = getattr(app.state, "impact_provider", None)
        if provider is not None:
            await provider.aclose()
            del app.state.impact_provider
            del app.state.impact_client


app = FastAPI(title="AI Backend", version="1.0.0", lifespan=lifespan)

app.include_router(health.router)
app.include_router(embeddings.router)
app.include_router(document_chunks.router)
app.include_router(impact.router)


@app.get("/")
async def root():
    return {"message": "FastAPI is running"}
