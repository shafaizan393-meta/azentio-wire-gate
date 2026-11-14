import json

from fastapi.testclient import TestClient

from wiresummary.app import create_app
from wiresummary.service import summarize_wire

UETR = "f47ac10b-58cc-4372-a567-0e02b2c3d479"


def held_wire() -> dict:
    return {
        "uetr": UETR,
        "status": "HELD",
        "reasons": [
            {
                "code": "HIGH_RISK_COUNTRY",
                "detail": "The creditor country is configured as high risk.",
            },
            {
                "code": "CORRIDOR_PURPOSE",
                "detail": "The purpose code is missing for corridor IN-AE.",
            },
        ],
        "amount": "25000.00",
        "currency": "INR",
        "fee": "750.00",
        "earmarkedAmount": "25750.00",
        "debtorAccountMasked": "****0001",
        "creditorAccountMasked": "****0001",
        "debtorAgentBic": "TESTINBBXXX",
        "creditorAgentBic": "DEMOAEADXXX",
        "corridor": "IN-AE",
    }


class FakeApiClient:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def get_wire(self, uetr: str) -> dict:
        assert uetr == UETR
        return self.payload


class FixedProvider:
    name = "test"

    def __init__(self, output: str) -> None:
        self.output = output
        self.prompt = ""

    def generate(self, prompt: str) -> str:
        self.prompt = prompt
        return self.output


def test_fake_provider_summarizes_held_wire_with_matching_citations() -> None:
    from wiresummary.providers import FakeLlmProvider

    result = summarize_wire(UETR, FakeApiClient(held_wire()), FakeLlmProvider())

    assert result.provider == "fake"
    assert result.citedReasonCodes == ["HIGH_RISK_COUNTRY", "CORRIDOR_PURPOSE"]
    assert result.suggestedAction == "ESCALATE"
    assert result.suggestedActionIsAdvisory is True


def test_unknown_reason_code_uses_factual_fallback() -> None:
    provider = FixedProvider(
        json.dumps(
            {
                "summary": "The payment was held for fraud.",
                "citedReasonCodes": ["FRAUD"],
                "suggestedAction": "REJECT",
            }
        )
    )

    result = summarize_wire(UETR, FakeApiClient(held_wire()), provider)

    assert result.provider == "fallback"
    assert result.citedReasonCodes == ["HIGH_RISK_COUNTRY", "CORRIDOR_PURPOSE"]
    assert "fraud" not in result.summary.lower()


def test_invalid_json_uses_factual_fallback() -> None:
    result = summarize_wire(
        UETR, FakeApiClient(held_wire()), FixedProvider("not JSON")
    )
    assert result.provider == "fallback"
    assert "high risk" in result.summary


def test_full_account_number_in_summary_uses_fallback() -> None:
    provider = FixedProvider(
        json.dumps(
            {
                "summary": "Account 123456789012 was held.",
                "citedReasonCodes": ["HIGH_RISK_COUNTRY"],
                "suggestedAction": "ESCALATE",
            }
        )
    )

    result = summarize_wire(UETR, FakeApiClient(held_wire()), provider)

    assert result.provider == "fallback"
    assert "123456789012" not in result.summary


def test_non_held_wire_returns_clear_error_from_endpoint() -> None:
    payload = held_wire()
    payload["status"] = "APPROVED"
    client = TestClient(create_app(FakeApiClient(payload), FixedProvider("{}")))

    response = client.post(f"/summaries/{UETR}")

    assert response.status_code == 409
    assert "not HELD" in response.json()["detail"]
