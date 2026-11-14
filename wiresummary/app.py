from fastapi import FastAPI, HTTPException

from wiresummary.client import WireApiClient, WireApiError
from wiresummary.models import SummaryResponse
from wiresummary.providers import LlmProvider, provider_from_environment
from wiresummary.service import summarize_wire


def create_app(
    api_client: WireApiClient | None = None,
    provider: LlmProvider | None = None,
) -> FastAPI:
    app = FastAPI(title="Held Wire Summary", version="0.1.0")
    wire_client = api_client or WireApiClient()
    llm_provider = provider or provider_from_environment()

    @app.post("/summaries/{uetr}", response_model=SummaryResponse)
    def create_summary(uetr: str) -> SummaryResponse:
        try:
            return summarize_wire(uetr, wire_client, llm_provider)
        except WireApiError as error:
            raise HTTPException(status_code=error.status_code, detail=str(error)) from error

    return app


app = create_app()
