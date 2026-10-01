#!/usr/bin/env bash
# 최초 1회: Let's Encrypt 인증서를 발급하고 nginx를 HTTPS로 올린다 (기능명세서 10장)
# 사전 조건: .env의 DOMAIN이 이 서버의 고정 IP를 가리키고(DNS A 레코드), 방화벽에서 80·443이 열려 있다
# 사용: ./deploy/init-letsencrypt.sh              실제 발급
#       STAGING=1 ./deploy/init-letsencrypt.sh    발급 한도 걱정 없는 연습(브라우저는 신뢰하지 않음)
#       FORCE=1 ./deploy/init-letsencrypt.sh      이미 발급된 인증서를 지우고 다시 발급(연습 인증서 교체 포함)
# 갱신은 crontab.example 의 certbot renew 가 맡는다
set -Eeuo pipefail
cd "$(dirname "$0")/.."

compose() { docker compose -f docker-compose.prod.yml "$@"; }
env_get() { { grep -E "^$1=" .env || true; } | tail -n1 | cut -d= -f2- | tr -d '\r'; }

[ -f .env ] || { echo ".env가 없어요. 먼저 .env.example을 참고해 만드세요." >&2; exit 1; }
domain=$(env_get DOMAIN)
email=$(env_get LETSENCRYPT_EMAIL)
[ -n "$domain" ] && [ -n "$email" ] || { echo ".env에 DOMAIN, LETSENCRYPT_EMAIL이 필요해요." >&2; exit 1; }
[[ $domain =~ ^[A-Za-z0-9.-]+$ ]] || { echo "DOMAIN 형식이 올바르지 않아요: $domain" >&2; exit 1; }

certbot_sh() { compose run --rm -T --no-deps --entrypoint sh certbot -c "$1"; }

# renewal 설정은 실제 발급 때만 생긴다 (임시 인증서에는 없음)
if [ "${FORCE:-0}" != 1 ] && certbot_sh "test -f /etc/letsencrypt/renewal/$domain.conf"; then
  echo "이미 $domain 인증서가 있어요. 다시 발급하려면 FORCE=1 로 실행하세요."
  exit 0
fi

trap 'echo "인증서 발급에 실패했어요. DNS·방화벽(80)을 확인하고 다시 실행하세요." >&2' ERR

# nginx가 443 설정을 읽으려면 인증서 파일이 있어야 하므로 임시(1일) 자체 서명 인증서를 먼저 둔다
echo "임시 인증서 생성"
certbot_sh "rm -rf /etc/letsencrypt/live/$domain /etc/letsencrypt/archive/$domain /etc/letsencrypt/renewal/$domain.conf \
  && mkdir -p /etc/letsencrypt/live/$domain \
  && openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
       -keyout /etc/letsencrypt/live/$domain/privkey.pem \
       -out /etc/letsencrypt/live/$domain/fullchain.pem -subj /CN=localhost"

echo "nginx 기동"
compose up -d --no-deps --force-recreate nginx
sleep 3

# 발급기가 live/ 아래를 새로 만들도록 임시 인증서를 지운다
certbot_sh "rm -rf /etc/letsencrypt/live/$domain"

echo "Let's Encrypt 인증서 발급"
staging=()
[ "${STAGING:-0}" = 1 ] && staging=(--staging)
compose run --rm -T certbot certonly --webroot -w /var/www/certbot \
  -d "$domain" --cert-name "$domain" --email "$email" \
  --agree-tos --no-eff-email --non-interactive "${staging[@]}"

compose exec -T nginx nginx -s reload
trap - ERR
echo "완료: https://$domain"
