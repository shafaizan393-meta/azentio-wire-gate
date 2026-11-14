from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class WireReason(BaseModel):
    code: str
    detail: str


class HeldWire(BaseModel):
    model_config = ConfigDict(extra="ignore")

    uetr: str
    status: str
    reasons: list[WireReason]
    amount: Decimal | None = None
    currency: str | None = None
    fee: Decimal | None = None
    earmarkedAmount: Decimal | None = None
    debtorAccountMasked: str | None = None
    creditorAccountMasked: str | None = None
    debtorAgentBic: str | None = None
    creditorAgentBic: str | None = None
    intermediaryAgentBic: str | None = None
    corridor: str | None = None


class ProviderDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary: str = Field(min_length=1, max_length=500)
    citedReasonCodes: list[str]
    suggestedAction: Literal["RELEASE", "REJECT", "ESCALATE"]


class SummaryResponse(BaseModel):
    uetr: str
    summary: str
    citedReasonCodes: list[str]
    suggestedAction: Literal["RELEASE", "REJECT", "ESCALATE"]
    suggestedActionIsAdvisory: Literal[True] = True
    provider: str
