#!/bin/zsh
set -euo pipefail
cd "${0:A:h}"
export PATH="/opt/homebrew/opt/node@22/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
exec node tools/unix.mjs launch --backend observer --profile crewcraft --home "$PWD/.crewcraft-home" --foreman-arg --no-notify
