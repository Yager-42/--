ARG GORSE_VERSION=0.5.6
ARG TARGETARCH

FROM debian:bookworm-slim AS downloader
ARG GORSE_VERSION
ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp
RUN case "${TARGETARCH}" in \
        amd64) GORSE_ARCH=amd64 ;; \
        arm64) GORSE_ARCH=arm64 ;; \
        *) echo "unsupported TARGETARCH=${TARGETARCH}" && exit 1 ;; \
    esac && \
    curl -fsSL -o gorse.zip "https://github.com/gorse-io/gorse/releases/download/v${GORSE_VERSION}/gorse_linux_${GORSE_ARCH}.zip" \
    && unzip gorse.zip -d /opt/gorse \
    && chmod +x /opt/gorse/gorse-*

FROM debian:bookworm-slim AS base
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=downloader /opt/gorse/gorse-* /usr/local/bin/
WORKDIR /var/lib/gorse

FROM base AS master
ENTRYPOINT ["gorse-master"]

FROM base AS server
ENTRYPOINT ["gorse-server"]

FROM base AS worker
ENTRYPOINT ["gorse-worker"]
