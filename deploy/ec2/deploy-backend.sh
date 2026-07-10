#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE_TAG="${1:-}"

if [[ -z "$IMAGE_TAG" ]]; then
  echo "Usage: $0 <image-tag>"
  echo "Example: $0 sha-3a3b71a"
  exit 64
fi

REGION="${AWS_REGION:-ap-southeast-1}"
ECR_REPO="${ECR_REPO:-shop-backend}"
CONTAINER_NAME="${CONTAINER_NAME:-shop-app}"
APP_DIR="${APP_DIR:-/home/ec2-user/shop}"
ENV_FILE="${ENV_FILE:-$APP_DIR/shop.env}"
LOG_GROUP="${LOG_GROUP:-/aws/ec2/shop-app}"
STREAM_PREFIX="${STREAM_PREFIX:-shop-web-server/shop-app}"
HOST_PORT="${HOST_PORT:-8081}"
CONTAINER_PORT="${CONTAINER_PORT:-8081}"
HEALTH_URL="${HEALTH_URL:-http://localhost:${HOST_PORT}/actuator/health}"

PREVIOUS_IMAGE_FILE="$APP_DIR/previous-image.txt"
CURRENT_IMAGE_FILE="$APP_DIR/current-image.txt"
STREAM_FILE="$APP_DIR/cloudwatch-stream.txt"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE"
  exit 66
fi

command -v aws >/dev/null
command -v docker >/dev/null
command -v curl >/dev/null

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
IMAGE_URI="${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG}"
STREAM_NAME="${STREAM_PREFIX}-$(date +%Y-%m-%dT%H-%M-%S)-${IMAGE_TAG}"

echo "Deploying image: $IMAGE_URI"

aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$ECR_REGISTRY"

docker pull "$IMAGE_URI"

PREVIOUS_IMAGE=""
if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  PREVIOUS_IMAGE="$(docker inspect "$CONTAINER_NAME" --format '{{.Config.Image}}')"
  echo "$PREVIOUS_IMAGE" > "$PREVIOUS_IMAGE_FILE"
  echo "Previous image: $PREVIOUS_IMAGE"
else
  echo "No existing container found."
fi

echo "$STREAM_NAME" > "$STREAM_FILE"
echo "CloudWatch stream: $STREAM_NAME"

run_container() {
  local image="$1"
  local stream="$2"

  docker run -d \
    --name "$CONTAINER_NAME" \
    --env-file "$ENV_FILE" \
    -p "${HOST_PORT}:${CONTAINER_PORT}" \
    --restart unless-stopped \
    --log-driver=awslogs \
    --log-opt awslogs-region="$REGION" \
    --log-opt awslogs-group="$LOG_GROUP" \
    --log-opt awslogs-stream="$stream" \
    "$image"
}

if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  docker rm -f "$CONTAINER_NAME"
fi

run_container "$IMAGE_URI" "$STREAM_NAME"

for attempt in {1..30}; do
  if curl -fsS "$HEALTH_URL" >/tmp/shop-health.json; then
    echo "$IMAGE_URI" > "$CURRENT_IMAGE_FILE"
    echo "Deploy succeeded."
    cat /tmp/shop-health.json
    exit 0
  fi

  echo "Waiting for health check... attempt=$attempt"
  sleep 2
done

echo "Deploy health check failed."
docker logs --tail 80 "$CONTAINER_NAME" || true

if [[ -n "$PREVIOUS_IMAGE" ]]; then
  ROLLBACK_STREAM="${STREAM_PREFIX}-$(date +%Y-%m-%dT%H-%M-%S)-rollback"
  echo "Rolling back to: $PREVIOUS_IMAGE"

  docker rm -f "$CONTAINER_NAME" || true
  echo "$ROLLBACK_STREAM" > "$STREAM_FILE"
  run_container "$PREVIOUS_IMAGE" "$ROLLBACK_STREAM"

  for attempt in {1..30}; do
    if curl -fsS "$HEALTH_URL" >/tmp/shop-health-rollback.json; then
      echo "$PREVIOUS_IMAGE" > "$CURRENT_IMAGE_FILE"
      echo "Rollback succeeded."
      cat /tmp/shop-health-rollback.json
      exit 1
    fi

    echo "Waiting for rollback health check... attempt=$attempt"
    sleep 2
  done

  echo "Rollback health check failed."
fi

exit 1