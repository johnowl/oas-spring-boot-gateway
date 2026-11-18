# The image of the container harnesses in this folder (specs/034-access-change-review.md, "Test harness").
FROM ubuntu:24.04
RUN apt-get update -qq \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git jq ca-certificates curl unzip openjdk-25-jdk-headless \
        > /dev/null \
    && rm -rf /var/lib/apt/lists/*
