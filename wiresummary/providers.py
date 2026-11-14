import json
import os
from typing import Protocol
from urllib.error import URLError
from urllib.request import Request, urlopen


class LlmProvider(Protocol):
    name: str

    def generate(self, prompt: str) -> str:
        """Return a JSON string containing a summary draft."""


class FakeLlmProvider:
    name = "fake"

    def generate(self, prompt: str) -> str:
        wire = json.loads(prompt.split("\nWIRE_DATA:\n", maxsplit=1)[1])
        reasons = wire["reasons"]
        details = "; ".join(reason["detail"] for reason in reasons)
        codes = [reason["code"] for reason in reasons]
        return json.dumps(
            {
                "summary": f"This wire was held because: {details}",
                "citedReasonCodes": codes,
                "suggestedAction": "ESCALATE",
            }
        )


class OllamaProvider:
    name = "ollama"

    def __init__(self, base_url: str, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model

    def generate(self, prompt: str) -> str:
        body = json.dumps(
            {"model": self.model, "prompt": prompt, "stream": False, "format": "json"}
        ).encode("utf-8")
        request = Request(
            f"{self.base_url}/api/generate",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=30) as response:
                result = json.loads(response.read().decode("utf-8"))
        except (URLError, TimeoutError, json.JSONDecodeError) as error:
            raise RuntimeError("The configured Ollama provider could not generate a summary.") from error
        if not isinstance(result, dict):
            raise RuntimeError("The configured Ollama provider returned an invalid response.")
        generated = result.get("response")
        if not isinstance(generated, str):
            raise RuntimeError("The configured Ollama provider returned no summary text.")
        return generated


def provider_from_environment() -> LlmProvider:
    provider = os.getenv("WIRESUMMARY_PROVIDER", "fake").lower()
    if provider == "fake":
        return FakeLlmProvider()
    if provider == "ollama":
        return OllamaProvider(
            os.getenv("OLLAMA_URL", "http://localhost:11434"),
            os.getenv("OLLAMA_MODEL", "qwen2.5:3b"),
        )
    raise ValueError(f"Unsupported WIRESUMMARY_PROVIDER: {provider}")
