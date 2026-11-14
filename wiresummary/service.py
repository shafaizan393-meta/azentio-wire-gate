import json
import re
from collections.abc import Callable

from pydantic import ValidationError

from wiresummary.client import WireApiClient, WireApiError
from wiresummary.models import HeldWire, ProviderDraft, SummaryResponse
from wiresummary.providers import LlmProvider

_UNMASKED_ACCOUNT = re.compile(r"\d{10,}")
_PROMPT_INSTRUCTIONS = (
    "Write one short factual summary using only the supplied wire data. "
    "Return JSON with summary, citedReasonCodes, and suggestedAction. "
    "Cited codes must come from the supplied reasons. The suggested action is advisory."
)


def build_prompt(wire: HeldWire) -> str:
    payload = {
        "uetr": wire.uetr,
        "amount": str(wire.amount) if wire.amount is not None else None,
        "currency": wire.currency,
        "fee": str(wire.fee) if wire.fee is not None else None,
        "earmarkedAmount": (
            str(wire.earmarkedAmount) if wire.earmarkedAmount is not None else None
        ),
        "debtorAccountMasked": wire.debtorAccountMasked,
        "creditorAccountMasked": wire.creditorAccountMasked,
        "debtorAgentBic": wire.debtorAgentBic,
        "creditorAgentBic": wire.creditorAgentBic,
        "intermediaryAgentBic": wire.intermediaryAgentBic,
        "corridor": wire.corridor,
        "reasons": [reason.model_dump() for reason in wire.reasons],
    }
    return f"{_PROMPT_INSTRUCTIONS}\nWIRE_DATA:\n{json.dumps(payload, sort_keys=True)}"


def summarize_wire(
    uetr: str,
    api_client: WireApiClient,
    provider: LlmProvider,
) -> SummaryResponse:
    try:
        wire = HeldWire.model_validate(api_client.get_wire(uetr))
    except ValidationError as error:
        raise WireApiError("The wire API returned an invalid wire response.") from error

    if wire.status != "HELD":
        raise WireApiError(f"Wire {uetr} is {wire.status}, not HELD.", 409)

    valid_codes = {reason.code for reason in wire.reasons}
    try:
        draft = ProviderDraft.model_validate_json(provider.generate(build_prompt(wire)))
        if not set(draft.citedReasonCodes).issubset(valid_codes):
            raise ValueError("The summary cited a reason code not present on the wire.")
        if _UNMASKED_ACCOUNT.search(draft.summary):
            raise ValueError("The summary contains an unmasked account number.")
    except (ValueError, ValidationError, json.JSONDecodeError):
        details = "; ".join(
            _UNMASKED_ACCOUNT.sub("[redacted]", reason.detail)
            for reason in wire.reasons
        )
        summary = f"This wire was held because: {details}" if details else "This wire was held."
        return SummaryResponse(
            uetr=wire.uetr,
            summary=summary,
            citedReasonCodes=[reason.code for reason in wire.reasons],
            suggestedAction="ESCALATE",
            provider="fallback",
        )

    return SummaryResponse(
        uetr=wire.uetr,
        summary=draft.summary,
        citedReasonCodes=draft.citedReasonCodes,
        suggestedAction=draft.suggestedAction,
        provider=provider.name,
    )
