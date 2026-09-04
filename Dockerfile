# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /source
COPY . .

# Build the application from the checked-out branch. The product build
# materializes both supported Linux architectures; ARCH selects the one copied
# into the runtime image below.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f portfolio-app/pom.xml --batch-mode --no-transfer-progress \
        -DskipTests package

FROM jlesage/baseimage-gui:ubuntu-26.04-v4

ARG ARCH=x86_64
ARG APP_VERSION=development

LABEL \
    org.opencontainers.image.description="Development build of Portfolio Performance with a browser-accessible GUI" \
    org.opencontainers.image.source="https://github.com/wollelam/portfolio" \
    org.opencontainers.image.title="Portfolio Performance Development" \
    org.opencontainers.image.vendor="Community"

ENV APP_NAME="Portfolio Performance Development" \
    APP_VERSION="${APP_VERSION}" \
    DOCKER_IMAGE_VERSION="${APP_VERSION}"

RUN add-pkg \
        openjdk-25-jre \
        libwebkit2gtk-4.1-0 && \
    install_app_icon.sh "https://www.portfolio-performance.info/images/logo.png"

COPY --from=builder \
    /source/portfolio-product/target/products/name.abuchen.portfolio.product/linux/gtk/${ARCH}/portfolio/ \
    /opt/portfolio/
COPY docker/rootfs/ /

RUN chmod -R a+rX /opt/portfolio && \
    sed -i '1i-data\n/config/portfolio' /opt/portfolio/PortfolioPerformance.ini
