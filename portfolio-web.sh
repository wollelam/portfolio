#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
image=portfolio-cli-build:latest
port=${PP_WEB_PORT:-8088}
poll_seconds=${PP_WEB_POLL_SECONDS:-30}
state=${PP_WEB_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/portfolio-web}
rebuild=false
build_only=false
detached=false
file=
demo=false

usage()
{
    printf 'Usage: %s [--rebuild] [--detach] [--demo | --file PATH] [--port PORT]\n' "$(basename -- "$0")"
    printf '       %s --build\n' "$(basename -- "$0")"
    printf 'Runs a local, read-only dashboard. Defaults to synthetic demo data.\n'
}

while (($#)); do
    case "$1" in
        --rebuild) rebuild=true ;;
        --build) rebuild=true; build_only=true ;;
        --detach) detached=true ;;
        --demo) demo=true ;;
        --file) file=${2:?--file requires a path}; shift ;;
        --port) port=${2:?--port requires a number}; shift ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
    shift
done
if [[ ! "$port" =~ ^[0-9]{1,5}$ ]] || ((10#$port < 1 || 10#$port > 65535)); then
    printf 'Port must be between 1 and 65535.\n' >&2
    exit 2
fi
port=$((10#$port))
if [[ ! "$poll_seconds" =~ ^[0-9]{1,4}$ ]] || ((10#$poll_seconds < 1 || 10#$poll_seconds > 3600)); then
    printf 'PP_WEB_POLL_SECONDS must be between 1 and 3600.\n' >&2
    exit 2
fi
poll_seconds=$((10#$poll_seconds))
if [[ -n "$file" ]]; then
    if [[ "$demo" == true || ! -f "$file" ]]; then
        printf 'Use --demo or --file with an existing file.\n' >&2
        exit 2
    fi
    file=$(realpath -- "$file")
fi
if ! command -v docker >/dev/null 2>&1; then
    printf 'Docker is required by this development launcher.\n' >&2
    exit 1
fi
if ! docker image inspect "$image" >/dev/null 2>&1; then
    printf 'Preparing the existing Java/Maven development image...\n'
    docker build -t "$image" "$repo/docker/cli-build"
fi
if ! docker volume inspect portfolio-cli-m2 >/dev/null 2>&1; then
    docker volume create portfolio-cli-m2 >/dev/null
fi
configuration="$repo/name.abuchen.portfolio.web.tests/target/work/configuration"
if [[ "$rebuild" == true || ! -f "$configuration/config.ini" ]]; then
    docker run --rm -v "$repo:/workspace" -v portfolio-cli-m2:/root/.m2 -w /workspace "$image" \
        mvn -B -f portfolio-app/pom.xml -Plocal-dev \
        -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.web,:name.abuchen.portfolio.web.tests \
        -am verify
fi
if [[ "$build_only" == true ]]; then
    exit 0
fi

mkdir -p -- "$state"
chmod 700 -- "$state"
state=$(realpath -- "$state")
arguments=(--demo)
mounts=()
if [[ -n "$file" ]]; then
    # A parent root can include a user-owned FUSE submount that Docker root cannot mount directly.
    file_root=$(realpath -- "${PP_WEB_FILE_ROOT:-$(dirname -- "$file")}")
    if [[ ! -d "$file_root" ]]; then
        printf 'PP_WEB_FILE_ROOT must be a directory.\n' >&2
        exit 2
    fi
    relative_file=$(realpath --relative-to="$file_root" -- "$file")
    if [[ "$relative_file" == .. || "$relative_file" == ../* || "$relative_file" == /* ]]; then
        printf 'Portfolio file must be inside PP_WEB_FILE_ROOT.\n' >&2
        exit 2
    fi
    mounts=(-v "$file_root:/portfolio-input:ro")
    arguments=(--file "/portfolio-input/$relative_file")
fi
if [[ "${PP_WEB_PRICE_UPDATES:-0}" == 1 ]]; then
    arguments+=(--price-updates-dir /web-state/price-updates)
fi
run_options=(--rm)
if [[ "$detached" == true ]]; then
    run_options=(--detach --restart unless-stopped --name "${PP_WEB_CONTAINER_NAME:-portfolio-web}")
    printf 'Starting Portfolio Web at http://localhost:%s in container %s.\n' "$port" "${PP_WEB_CONTAINER_NAME:-portfolio-web}"
else
    printf 'Starting Portfolio Web at http://localhost:%s (Ctrl-C to stop).\n' "$port"
fi
exec docker run "${run_options[@]}" --init --user "$(id -u):$(id -g)" --entrypoint /bin/sh -p "127.0.0.1:$port:$port" \
    -v "$repo:/workspace:ro" -v portfolio-cli-m2:/maven:ro -v "$state:/web-state" \
    "${mounts[@]}" -e PP_WEB_PASSWORD -w /workspace "$image" -c '
        mkdir -p /web-state/configuration
        sed "s#/root/.m2/#/maven/#g" /workspace/name.abuchen.portfolio.web.tests/target/work/configuration/config.ini > /web-state/configuration/config.ini
        exec "$@"
    ' -- \
    java -Dosgi.clean=true -Duser.home=/web-state \
    -Dsun.net.client.defaultConnectTimeout=15000 -Dsun.net.client.defaultReadTimeout=30000 \
    -jar /maven/repository/p2/osgi/bundle/org.eclipse.equinox.launcher/1.7.100.v20251111-0406/org.eclipse.equinox.launcher-1.7.100.v20251111-0406.jar \
    -data /web-state/workspace \
    -configuration /web-state/configuration \
    -application name.abuchen.portfolio.web.application \
    --bind 0.0.0.0 --port "$port" --poll-seconds "$poll_seconds" --snapshot-dir /web-state/snapshots "${arguments[@]}"
