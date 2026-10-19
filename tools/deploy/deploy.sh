#!/usr/bin/env bash
# Python 3 is included in Ubuntu 24.04. No secrets are sourced into a shell.
set -euo pipefail
exec python3 "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/deploy.py" "$@"
