"""Export the backend's generated OpenAPI contract to a deterministic JSON file."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.main import openapi_document


def main() -> None:
    """Write the generated OpenAPI schema to the requested local path."""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("openapi.json"),
        help="Output JSON path (default: openapi.json)",
    )
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(openapi_document(), ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
