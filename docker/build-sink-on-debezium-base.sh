#!/bin/bash

# Production docker image builder
set -e

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
SRC_ROOT="$(realpath "${CUR_DIR}/..")"

# Externally configurable build-dependent options
TAG="${TAG:-latest}"
DOCKER_IMAGE="altinity/clickhouse-kafka-sink-connector-on-debezium-base:${TAG}"

# Externally configurable build-dependent options
DOCKERFILE_DIR="${SRC_ROOT}/docker"
DOCKERFILE="${DOCKERFILE_DIR}/Dockerfile-sink-on-debezium-base-image"

echo "***************"
echo "* Build image *"
echo "***************"
DOCKER_CMD="docker build -t ${DOCKER_IMAGE} -f ${DOCKERFILE} ${SRC_ROOT}"

if ${DOCKER_CMD}; then
    echo "ALL DONE"
else
    echo "FAILED"
    exit 1
fi
