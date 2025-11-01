FROM maven:3.9-eclipse-temurin-21-noble

LABEL org.opencontainers.image.source=https://github.com/flip-flop-foundry/talos-kms-sever
LABEL org.opencontainers.image.description="Docker image for building Debian packages for Talos KMS Server"

RUN apt-get update && apt-get install -y \
    fakeroot