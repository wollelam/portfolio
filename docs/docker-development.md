# Development container

This container builds Portfolio Performance from the currently checked-out
source instead of downloading a published release. It uses the same
browser-accessible GUI base image as the community Portfolio Performance
container.

## Build and run

From the repository root:

```sh
docker compose up --build -d
```

Open <http://localhost:5800> after the container starts. The initial image
build compiles the complete Eclipse product and can take several minutes.

The Compose setup persists application settings and portfolio files below the
ignored `volume/` directory:

- `volume/config` contains the Eclipse workspace and container configuration.
- `volume/data` contains portfolio files and is available inside the
  application as `/opt/portfolio/data`.

Stop the container with:

```sh
docker compose down
```

## Configuration

The defaults can be overridden with environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PP_WEB_PORT` | `5800` | Browser port on the host |
| `PP_ARCH` | `x86_64` | Portfolio product architecture (`x86_64` or `aarch64`) |
| `PP_APP_VERSION` | `development` | Version shown in the container metadata |
| `USER_ID` | `1000` | User ID used for persisted files |
| `GROUP_ID` | `1000` | Group ID used for persisted files |
| `TZ` | `Europe/Zurich` | Container timezone |
| `DISPLAY_WIDTH` | `1920` | Virtual display width |
| `DISPLAY_HEIGHT` | `1080` | Virtual display height |

For example:

```sh
PP_WEB_PORT=5801 USER_ID=$(id -u) GROUP_ID=$(id -g) docker compose up --build -d
```

Rebuild the image after switching branches or changing source code. Docker
reuses the dependency and build layers when possible.
