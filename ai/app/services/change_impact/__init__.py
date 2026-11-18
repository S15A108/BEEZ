"""Internal groundwork for AI-A03; no HTTP route or persistence is implied."""

from .models import ImpactInput, ImpactResult
from .service import ImpactAnalysisService, ImpactModelConfig

__all__ = ["ImpactAnalysisService", "ImpactInput", "ImpactModelConfig", "ImpactResult"]
