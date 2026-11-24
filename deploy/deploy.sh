#!/usr/bin/env bash
set -euo pipefail

TAG=${1:?사용법: deploy.sh <이미지 태그>}
BASE=/opt/moa
STATE=$BASE/state
ENV_FILE=/etc/moa/moa.env
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$BASE/docker-compose.yml")
HEALTH_WAIT=${HEALTH_WAIT:-45}

mkdir -p "$STATE"
exec 9>"$STATE/deploy.lock"
flock -n 9 || { echo "다른 배포가 진행 중이에요"; exit 1; }

PREV=$(cat "$STATE/current_tag" 2>/dev/null || true)

run_tag() {
  export MOA_TAG=$1
  "${COMPOSE[@]}" pull app
  "${COMPOSE[@]}" up -d mysql
  "${COMPOSE[@]}" up -d --no-deps app
}

healthy() {
  sleep "$HEALTH_WAIT"
  local cid state
  cid=$("${COMPOSE[@]}" ps -q app | head -n1)
  [[ -n $cid ]] || return 1
  state=$(docker inspect -f '{{.State.Running}} {{.RestartCount}}' "$cid")
  echo "app 상태: running/restarts = $state"
  [[ $state == "true 0" ]]
}

echo "== 배포: ${PREV:-없음} -> $TAG =="
run_tag "$TAG"

if healthy; then
  echo "$PREV" > "$STATE/previous_tag"
  echo "$TAG" > "$STATE/current_tag"
  "${COMPOSE[@]}" logs --tail 30 app
  docker image prune -af --filter "until=168h" >/dev/null || true
  echo "== 배포 성공: $TAG =="
  exit 0
fi

echo "== 새 버전이 정상 기동하지 않았어요. 로그: =="
"${COMPOSE[@]}" logs --tail 60 app || true
if [[ -n $PREV ]]; then
  echo "== 롤백: $PREV =="
  run_tag "$PREV"
  healthy && echo "== 롤백 완료 ==" || echo "== 롤백 버전도 비정상. 수동 확인 필요 =="
fi
exit 1
