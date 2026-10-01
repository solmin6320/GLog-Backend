#!/usr/bin/env bash
# 서버에서 실행: 받은 이미지로 앱을 교체하고, 헬스체크가 실패하면 이전 이미지로 되돌린다
# 사용: ./deploy/deploy.sh ghcr.io/<소유자>/<저장소>:<태그>
#   레지스트리 로그인은 호출 쪽(GitHub Actions)이 먼저 한다. 이미지는 로컬에서 jandilog-app:current 로 쓴다
# 롤백은 앱 이미지만 되돌린다. Flyway 마이그레이션은 앞으로만 가므로 스키마는 되돌리지 않는다
set -euo pipefail
cd "$(dirname "$0")/.."

image=${1:?"사용: deploy.sh <이미지:태그>"}
[[ $image =~ ^[a-z0-9][a-z0-9./_:@-]*$ ]] || { echo "이미지 이름 형식이 올바르지 않아요: $image" >&2; exit 1; }
[ -f .env ] || { echo ".env가 없어요. 서버에 먼저 만들어야 해요." >&2; exit 1; }
[ -z "$(find .env -perm /077 2>/dev/null)" ] || echo "경고: .env 권한이 600보다 넓어요 (chmod 600 .env)" >&2

compose() { docker compose -f docker-compose.prod.yml "$@"; }
env_get() { { grep -E "^$1=" .env || true; } | tail -n1 | cut -d= -f2- | tr -d '\r'; }

# 앱이 healthy가 될 때까지 최대 3분 기다린다. 기동 중 한 번이라도 재시작되면(크래시) 바로 실패로 본다
wait_healthy() {
  local status state restarts
  for _ in $(seq 1 36); do
    state=$(docker inspect -f '{{.State.Status}}' jandilog-prod-app 2>/dev/null || echo missing)
    status=$(docker inspect -f '{{.State.Health.Status}}' jandilog-prod-app 2>/dev/null || echo missing)
    restarts=$(docker inspect -f '{{.RestartCount}}' jandilog-prod-app 2>/dev/null || echo 0)
    [ "$status" = healthy ] && return 0
    [ "$status" = unhealthy ] && return 1
    [ "$restarts" -gt 0 ] && return 1
    case $state in exited|dead|restarting|missing) return 1 ;; esac
    sleep 5
  done
  return 1
}

had_previous=0
if docker image inspect jandilog-app:current >/dev/null 2>&1; then
  docker tag jandilog-app:current jandilog-app:previous
  had_previous=1
fi

echo "이미지 받기: $image"
docker pull "$image"
docker tag "$image" jandilog-app:current
# 레지스트리 이름표는 떼어 둔다 (current·previous만 남기려는 정리)
docker rmi "$image" >/dev/null 2>&1 || true

echo "MariaDB 확인"
compose up -d --wait --wait-timeout 120 mariadb

echo "앱 교체"
compose up -d --no-deps --force-recreate app
if ! wait_healthy; then
  echo "헬스체크 실패. 앱 로그 마지막 80줄:" >&2
  docker logs --tail 80 jandilog-prod-app >&2 || true
  if [ "$had_previous" = 1 ]; then
    echo "이전 이미지로 되돌려요" >&2
    docker tag jandilog-app:previous jandilog-app:current
    compose up -d --no-deps --force-recreate app
    if wait_healthy; then echo "이전 이미지로 복구됨" >&2; else echo "되돌린 뒤에도 앱이 비정상이에요" >&2; fi
  else
    echo "되돌릴 이전 이미지가 없어요 (첫 배포)" >&2
  fi
  docker image prune -f >/dev/null || true
  exit 1
fi

# 프록시 설정(템플릿)이 바뀌었을 수 있어 컨테이너를 다시 만들어 반영한다
echo "nginx 반영"
compose up -d --no-deps --force-recreate nginx

domain=$(env_get DOMAIN)
echo "HTTPS 확인: https://$domain/actuator/health"
for _ in $(seq 1 12); do
  if curl -fsS --max-time 10 --resolve "$domain:443:127.0.0.1" "https://$domain/actuator/health" | grep -q '"status":"UP"'; then
    docker image prune -f >/dev/null
    echo "배포 완료: $image"
    exit 0
  fi
  sleep 5
done
echo "HTTPS 헬스체크 실패. nginx 로그:" >&2
docker logs --tail 40 jandilog-prod-nginx >&2 || true
exit 1
