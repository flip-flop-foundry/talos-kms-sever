FROM maven:3.9-eclipse-temurin-21-noble

LABEL org.opencontainers.image.title="talos-kms-builder"
LABEL org.opencontainers.image.description="Docker image for building Debian packages for Talos KMS Server"
LABEL org.opencontainers.image.source="https://github.com/flip-flop-foundry/talos-kms-sever"
LABEL org.opencontainers.image.url="https://github.com/flip-flop-foundry/talos-kms-sever"
LABEL org.opencontainers.image.authors="flip-flop-foundry"

RUN apt-get update && apt-get install -y \
    fakeroot \
  && rm -rf /var/lib/apt/lists/*
