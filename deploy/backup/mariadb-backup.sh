#!/usr/bin/env bash
# MariaDB 덤프를 gzip으로 묶어 S3에 올린다 (기능명세서 10장, DB명세서 5-2). 일 1회 cron(crontab.example)
# MongoDB는 Atlas가 백업하므로 여기서 다루지 않는다
# 보관 기간·오래된 백업 삭제는 기획서에 값이 없어 이 스크립트에 넣지 않았다
#
# 읽는 값(.env): BACKUP_S3_BUCKET, BACKUP_AWS_REGION, BACKUP_AWS_ACCESS_KEY_ID, BACKUP_AWS_SECRET_ACCESS_KEY,
#               BACKUP_S3_PREFIX(선택, 기본 mariadb/)
# S3 키: <prefix>jandilog-YYYYmmdd-HHMMSS.sql.gz (서울 시각)
#
# 복구(새 서버 또는 빈 DB에): S3에서 파일을 받은 뒤
#   gunzip -c jandilog-YYYYmmdd-HHMMSS.sql.gz | docker exec -i jandilog-prod-mariadb \
#     sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"'
set -euo pipefail
umask 077
cd "$(dirname "$0")/../.."

env_get() { { grep -E "^$1=" .env || true; } | tail -n1 | cut -d= -f2- | tr -d '\r'; }
log() { echo "[$(TZ=Asia/Seoul date '+%F %T')] $*"; }

bucket=$(env_get BACKUP_S3_BUCKET)
region=$(env_get BACKUP_AWS_REGION)
prefix=$(env_get BACKUP_S3_PREFIX)
prefix=${prefix:-mariadb/}
[[ $prefix == */ ]] || prefix="$prefix/"
AWS_ACCESS_KEY_ID=$(env_get BACKUP_AWS_ACCESS_KEY_ID)
AWS_SECRET_ACCESS_KEY=$(env_get BACKUP_AWS_SECRET_ACCESS_KEY)
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
export AWS_DEFAULT_REGION=$region

for v in bucket region AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  [ -n "${!v}" ] || { log "필요한 값이 비어 있어요: $v (.env의 BACKUP_* 확인)" >&2; exit 1; }
done

name="jandilog-$(TZ=Asia/Seoul date +%Y%m%d-%H%M%S).sql.gz"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
file="$tmp/$name"

# 앱 계정으로 덤프한다 (DB 전체 권한이 있는 계정이라 root 비밀번호가 필요 없다). 읽기 일관성은 단일 트랜잭션으로 맞춘다
log "덤프 시작"
docker exec -i jandilog-prod-mariadb sh -c \
  'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u"$MARIADB_USER" --single-transaction --routines --triggers --events --default-character-set=utf8mb4 "$MARIADB_DATABASE"' \
  | gzip -9 > "$file"

# 중간에 끊긴 덤프를 올리지 않도록 압축 무결성과 마지막 줄(Dump completed)을 확인한다
gzip -t "$file"
gunzip -c "$file" | tail -n 1 | grep -q 'Dump completed' || { log "덤프가 끝까지 만들어지지 않았어요" >&2; exit 1; }
log "덤프 완료: $(du -h "$file" | cut -f1)"

# 서버에 AWS CLI를 설치하지 않고 공식 이미지로 올린다. 키는 환경변수로만 넘긴다
docker run --rm \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_DEFAULT_REGION \
  -v "$file:/backup/$name:ro" \
  amazon/aws-cli:2.37.7 s3 cp "/backup/$name" "s3://$bucket/$prefix$name" --only-show-errors
log "업로드 완료: s3://$bucket/$prefix$name"
