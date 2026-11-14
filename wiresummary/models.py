from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class WireReason(BaseModel):
    code: str = Field(pattern=r"^[A-Z][A-Z0-9_]*$")
    detail: str = Field(max_length=500)


class HeldWire(BaseModel):
    model_config = ConfigDict(extra="ignore")

    uetr: str = Field(
        pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    status: str
    reasons: list[WireReason]
    amount: Decimal | None = Field(default=None, gt=0, max_digits=10, decimal_places=2)
    currency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    fee: Decimal | None = Field(default=None, ge=0, max_digits=12, decimal_places=2)
    earmarkedAmount: Decimal | None = Field(
        default=None, ge=0, max_digits=12, decimal_places=2
    )
    debtorAccountMasked: str | None = None
    creditorAccountMasked: str | None = None
    debtorAgentBic: str | None = Field(
        default=None, pattern=r"^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$"
    )
    creditorAgentBic: str | None = Field(
        default=None, pattern=r"^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$"
    )
    intermediaryAgentBic: str | None = Field(
        default=None, pattern=r"^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$"
    )
    corridor: str | None = Field(default=None, pattern=r"^[A-Z]{2}-[A-Z]{2}$")


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
