import json
import os
from decimal import Decimal
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class WireApiError(RuntimeError):
    def __init__(self, message: str, status_code: int = 502) -> None:
        super().__init__(message)
        self.status_code = status_code


class WireApiClient:
    def __init__(self, base_url: str | None = None) -> None:
        self.base_url = (base_url or os.getenv(
            "WIRE_API_URL", "http://localhost:8080"
        )).rstrip("/")

    def get_wire(self, uetr: str) -> dict:
        request = Request(f"{self.base_url}/api/v1/wires/{uetr}")
        try:
            with urlopen(request, timeout=10) as response:
                payload = json.loads(
                    response.read().decode("utf-8"), parse_float=Decimal
                )
        except HTTPError as error:
            if error.code == 404:
                raise WireApiError(f"No wire exists for UETR {uetr}.", 404) from error
            raise WireApiError("The wire API could not retrieve the wire.") from error
        except (URLError, TimeoutError, json.JSONDecodeError) as error:
            raise WireApiError("The wire API could not retrieve the wire.") from error
        if not isinstance(payload, dict):
            raise WireApiError("The wire API returned an invalid response.")
        return payload
