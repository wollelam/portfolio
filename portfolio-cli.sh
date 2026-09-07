#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
maven_volume=portfolio-cli-m2
build_image_repository=portfolio-cli-build
build_image_tag=$(sha256sum "$script_dir/docker/cli-build/Dockerfile" | cut -c1-16)
maven_image="$build_image_repository:$build_image_tag"
maven_image_alias="$build_image_repository:latest"
runtime_config="$script_dir/name.abuchen.portfolio.cli.tests/target/work/configuration/config.ini"
launcher=/root/.m2/repository/p2/osgi/bundle/org.eclipse.equinox.launcher/1.7.100.v20251111-0406/org.eclipse.equinox.launcher-1.7.100.v20251111-0406.jar

usage()
{
	printf 'Usage: %s [--rebuild] [--build-linux|--run-linux] [--package-linux] [file.portfolio]\n' "$(basename -- "$0")"
	printf '  --rebuild  Rebuild and test the CLI before launching it.\n'
	printf '  --build-linux  Materialize the standalone Linux executable without archiving it.\n'
	printf '  --run-linux  Rebuild and launch the standalone Linux CLI, optionally opening a file.\n'
	printf '  --package-linux  Build the standalone Linux x86_64 archive in the Docker container.\n'
	printf '  file       Open this client file when the shell starts.\n'
}

rebuild=false
build_linux=false
run_linux=false
package_linux=false
portfolio_file=
while (($#)); do
    case "$1" in
        --rebuild) rebuild=true ;;
        --build-linux) build_linux=true ;;
        --run-linux) run_linux=true ;;
        --package-linux) package_linux=true ;;
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

if [[ ("$build_linux" == true || "$package_linux" == true) && -n "$portfolio_file" ]]; then
    usage >&2
    exit 2
fi

if [[ ("$build_linux" == true && "$run_linux" == true) || ("$build_linux" == true && "$package_linux" == true) \
    || ("$run_linux" == true && "$package_linux" == true) || ("$rebuild" == true && ("$build_linux" == true \
    || "$run_linux" == true || "$package_linux" == true)) ]]; then
    usage >&2
    exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
    printf 'Docker is required to run the development CLI.\n' >&2
    exit 1
fi

if ! docker volume inspect "$maven_volume" >/dev/null 2>&1; then
    docker volume create "$maven_volume" >/dev/null
fi

ensure_build_image()
{
    if docker image inspect "$maven_image" >/dev/null 2>&1; then
        return
    fi

    printf 'Preparing cached Docker build image %s...\n' "$maven_image"
    docker build --quiet \
        --tag "$maven_image" \
        --tag "$maven_image_alias" \
        "$script_dir/docker/cli-build" >/dev/null
}

ensure_build_image

clear_tmp_build()
{
    local build_root=$1
    if [[ -d "$build_root" ]]; then
        docker run --rm \
            -v "$build_root:/workspace" \
            "$maven_image" \
            bash -lc 'rm -rf /workspace/* /workspace/.[!.]* /workspace/..?*'
    fi
}

if [[ "$package_linux" == true ]]; then
    printf 'Building the standalone Linux CLI archive in Docker...\n'
    docker run --rm \
        -v "$script_dir:/workspace" \
        -v "$maven_volume:/root/.m2" \
        -w /workspace \
        "$maven_image" \
        bash -lc 'set -euo pipefail
            Xvfb :99 -screen 0 1280x1024x24 >/tmp/xvfb.log 2>&1 &
            export DISPLAY=:99
            mvn -q -f portfolio-app/pom.xml -Ppackage-distro -DskipTests install'

    package_archive=$(find "$script_dir/portfolio-product/target/products" \
        -maxdepth 1 -type f -name 'PortfolioPerformance-CLI-*-linux.gtk.x86_64.tar.gz' \
        -print | sort | tail -n 1)
    if [[ -z "$package_archive" ]]; then
        printf 'The Linux CLI archive was not produced.\n' >&2
        exit 1
    fi
    printf 'Standalone Linux CLI archive:\n%s\n' "$package_archive"
    exit 0
fi

if [[ "$build_linux" == true || "$run_linux" == true ]]; then
    build_root=/tmp/portfolio-cli-build
    clear_tmp_build "$build_root"
    mkdir -p -- "$build_root"

    printf 'Copying the working tree to tmpfs...\n'
    tar -C "$script_dir" \
        --exclude=.git \
        --exclude=target \
        --exclude='*/target' \
        --exclude=volume \
        --exclude=portfolio-cli \
        -cf - . | tar -C "$build_root" -xf -

    printf 'Building the standalone Linux CLI executable in Docker...\n'
    if ! docker run --rm \
        -v "$build_root:/workspace" \
        -v "$maven_volume:/root/.m2" \
        -w /workspace \
        "$maven_image" \
        bash -lc 'set -euo pipefail
            Xvfb :99 -screen 0 1280x1024x24 >/tmp/xvfb.log 2>&1 &
            export DISPLAY=:99
            mvn -q -f portfolio-app/pom.xml -Ppackage-distro -DskipTests package'; then
        clear_tmp_build "$build_root"
        rmdir -- "$build_root" 2>/dev/null || true
        exit 1
    fi

    linux_product="$build_root/portfolio-product/target/products/name.abuchen.portfolio.cli.product/linux/gtk/x86_64/portfolio-cli"
    if [[ ! -x "$linux_product/portfolio-cli" ]]; then
        printf 'The Linux CLI executable was not produced.\n' >&2
        exit 1
    fi
    printf 'Standalone Linux CLI executable:\n%s/portfolio-cli\n' "$linux_product"
    printf 'Build directory (tmpfs):\n%s\n' "$build_root"

    if [[ "$run_linux" == true ]]; then
        if [[ -n "$portfolio_file" ]]; then
            exec "$linux_product/portfolio-cli" "$portfolio_file"
        fi
        exec "$linux_product/portfolio-cli"
    fi

    exit 0
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
