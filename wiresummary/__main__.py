import argparse
import json
import sys

from wiresummary.client import WireApiClient, WireApiError
from wiresummary.providers import provider_from_environment
from wiresummary.service import summarize_wire


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize a held wire decision.")
    parser.add_argument("uetr")
    arguments = parser.parse_args()
    try:
        result = summarize_wire(
            arguments.uetr, WireApiClient(), provider_from_environment()
        )
    except WireApiError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result.model_dump(), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
