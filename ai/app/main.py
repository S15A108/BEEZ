from fastapi import FastAPI
from app.routers import health

app = FastAPI(
    title="AI Backend",
    version="1.0.0"
)

app.include_router(health.router)


@app.get("/")
async def root():
    return {"message": "FastAPI is running"}
