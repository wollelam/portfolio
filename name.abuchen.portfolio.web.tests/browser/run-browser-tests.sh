#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
browser_dir="$repo/name.abuchen.portfolio.web.tests/browser"
port=${PP_WEB_BROWSER_TEST_PORT:-8098}
suffix="${UID:-user}-$$"
web_name="portfolio-web-browser-$suffix"
collector_name="portfolio-web-quotes-browser-$suffix"
quote_image="portfolio-web-quotes:browser-test-$suffix"
scratch_root="$HOME/.codex/tmp"
mkdir -p -- "$scratch_root"
chmod 700 "$scratch_root"
state=$(mktemp -d "$scratch_root/portfolio-web-browser.XXXXXX")
chmod 700 "$state"
cleanup() {
    docker rm -f "$collector_name" "$web_name" >/dev/null 2>&1 || true
    docker image rm "$quote_image" >/dev/null 2>&1 || true
    rm -rf -- "$state"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$repo"
if [[ "${PP_WEB_BROWSER_SKIP_BUILD:-0}" != 1 ]]; then
    ./portfolio-web.sh --build
fi
docker build -t "$quote_image" quote-stream
mkdir -m 700 "$state/live-quotes"
PP_WEB_STATE_DIR="$state" PP_WEB_LIVE_QUOTES=1 PP_WEB_CONTAINER_NAME="$web_name" PP_WEB_PORT="$port" \
    ./portfolio-web.sh --detach --demo --port "$port"
docker run --detach --name "$collector_name" --init --network none --user "$(id -u):$(id -g)" \
    -v "$state/live-quotes:/state" "$quote_image" --demo --directory /state >/dev/null

for attempt in $(seq 1 120); do
    if curl --fail --silent "http://127.0.0.1:$port/api/dashboard" >/dev/null \
      && curl --fail --silent "http://127.0.0.1:$port/api/price-updates" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.exit(JSON.parse(s).liveQuotes?.mode === "demo" ? 0 : 1))'; then
        break
    fi
    if [[ "$attempt" == 120 ]]; then
        docker logs "$web_name" >&2 || true
        docker logs "$collector_name" >&2 || true
        printf 'Demo server and simulated quote feed did not become ready.\n' >&2
        exit 1
    fi
    sleep 1
done

cd "$browser_dir"
BASE_URL="http://127.0.0.1:$port" npm test
