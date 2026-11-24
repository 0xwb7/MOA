#!/usr/bin/env bash
# 서버 위치: /opt/moa/deploy.sh   (SSM이 root로 실행)
# 사용법: /opt/moa/deploy.sh <이미지 태그(커밋 SHA)>
# 새 버전이 45초 안에 죽거나 재시작 루프에 빠지면 직전 태그로 자동 롤백한다.
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
  "${COMPOSE[@]}" pull --quiet app
  # --no-deps로 app을 띄우면 depends_on(healthy) 대기가 무시된다. 그래서 MySQL이 healthy가 될 때까지 여기서 기다린다
  "${COMPOSE[@]}" up -d --wait --wait-timeout 120 mysql
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
exit
