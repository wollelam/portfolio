#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
maven_volume=portfolio-cli-m2
maven_image=maven:3.9.11-eclipse-temurin-21
runtime_config="$script_dir/name.abuchen.portfolio.cli.tests/target/work/configuration/config.ini"
launcher=/root/.m2/repository/p2/osgi/bundle/org.eclipse.equinox.launcher/1.7.100.v20251111-0406/org.eclipse.equinox.launcher-1.7.100.v20251111-0406.jar

usage()
{
    printf 'Usage: %s [--rebuild] [file.portfolio]\n' "$(basename -- "$0")"
    printf '  --rebuild  Rebuild and test the CLI before launching it.\n'
    printf '  file       Open this client file when the shell starts.\n'
}

rebuild=false
portfolio_file=
while (($#)); do
    case "$1" in
        --rebuild) rebuild=true ;;
        --help|-h)
            usage
            exit 0
            ;;
        -*)
            usage >&2
            exit 2
            ;;
        *)
            if [[ -n "$portfolio_file" || ! -f "$1" ]]; then
                usage >&2
                exit 2
            fi
            portfolio_file=$(cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")
            ;;
    esac
    shift
done

if ! command -v docker >/dev/null 2>&1; then
    printf 'Docker is required to run the development CLI.\n' >&2
    exit 1
fi

if ! docker volume inspect "$maven_volume" >/dev/null 2>&1; then
    docker volume create "$maven_volume" >/dev/null
fi

if [[ "$rebuild" == true || ! -f "$runtime_config" ]]; then
    printf 'Building and testing Portfolio Performance CLI...\n'
    docker run --rm \
        -v "$script_dir:/workspace" \
        -v "$maven_volume:/root/.m2" \
        -w /workspace \
        "$maven_image" \
        mvn -q -f portfolio-app/pom.xml -Plocal-dev \
        -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.cli,:name.abuchen.portfolio.cli.tests \
        -am verify
fi

printf 'Starting Portfolio Performance CLI...\n'
docker_arguments=(--rm -it -v "$script_dir:/workspace" -v "$maven_volume:/root/.m2" -w /workspace)
application_arguments=()
if [[ -n "$portfolio_file" ]]; then
    docker_arguments+=(-v "$(dirname -- "$portfolio_file"):/portfolio-input")
    application_arguments=(/portfolio-input/"$(basename -- "$portfolio_file")")
fi

exec docker run "${docker_arguments[@]}" \
    "$maven_image" \
    java -Dosgi.clean=true \
    -jar "$launcher" \
    -data /tmp/portfolio-cli-data \
    -configuration /workspace/name.abuchen.portfolio.cli.tests/target/work/configuration \
    -application name.abuchen.portfolio.cli.application \
    -consoleLog \
    "${application_arguments[@]}"
