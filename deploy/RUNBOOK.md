# 운영 안내 (Lightsail 2GB)

근거: 기능명세서 10장, DB명세서 5-1·5-2. 값(도메인·계정·키)은 이 문서에 쓰지 않는다.

## 구성

```
인터넷 -> nginx(80/443, Let's Encrypt) -> app(Spring Boot, 내부 8080) -> mariadb(내부)
                                                   +-> MongoDB Atlas M0 / Upstash Redis (외부 관리형)
```

- 파일: `docker-compose.prod.yml`, `deploy/nginx/`, `deploy/mariadb/`, `deploy/deploy.sh`, `deploy/init-letsencrypt.sh`, `deploy/backup/`, `deploy/crontab.example`, `.github/workflows/deploy.yml`
- 서버 경로: 배포 사용자 홈의 `~/jandilog`. 로컬 개발용 `docker-compose.yml`과 이름이 겹치지 않게 컨테이너는 `jandilog-prod-*`.
- 외부에 열리는 것은 80·443뿐이다. MariaDB·앱 포트는 호스트에 열지 않는다. `/actuator`는 `/actuator/health`만 외부에 열린다(Redis 항목 제외).

## 최초 서버 준비 (사람이 한다)

1. Lightsail 2GB 인스턴스 + 고정 IP. 방화벽(네트워킹)에서 22, 80, 443 허용.
2. DNS: 도메인 A 레코드를 고정 IP로.
3. Docker Engine + Compose 플러그인 설치, 배포 사용자를 `docker` 그룹에 추가.
4. 권장: swap 1GB. 컨테이너 메모리 상한을 넘기 전에 급한 불을 받아 준다.
   ```
   sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   ```
5. 배포 전용 SSH 키를 만들어 공개키를 서버 `authorized_keys`에 넣는다 (`ssh-keygen -t ed25519`).
6. 서버에 `~/jandilog/.env` 작성 (`.env.example` 참고, 운영 항목 포함) 후 `chmod 600 ~/jandilog/.env`.
7. 운영 파일을 처음 한 번 서버로 올린다 (이후는 배포 워크플로가 같은 방식으로 올린다). 저장소 루트에서:
   ```
   tar czf - docker-compose.prod.yml deploy | ssh <사용자>@<호스트> "mkdir -p jandilog && tar xzf - -C jandilog"
   ```
8. 서버에서 인증서 발급: `cd ~/jandilog && STAGING=1 ./deploy/init-letsencrypt.sh` 로 연습한 뒤 `FORCE=1 ./deploy/init-letsencrypt.sh` 로 실제 발급.
9. 서버에서 `crontab -e`로 `deploy/crontab.example` 내용 등록 (백업 일 1회, 인증서 갱신).
10. GitHub Secrets 4개 등록 (아래). 이후 main에서 CI가 성공하면 자동 배포되고, Actions의 Deploy를 수동 실행(main)해도 된다.

## 시크릿 관리

원칙: 저장소에는 키 이름만 둔다(`.env.example`). 값은 아래 한 곳에만 둔다.

| 종류 | 위치 | 비고 |
|---|---|---|
| 배포 접속: `LIGHTSAIL_HOST`, `LIGHTSAIL_USER`, `LIGHTSAIL_SSH_KEY`, `LIGHTSAIL_KNOWN_HOSTS` | GitHub Secrets | 배포 전용 키. `LIGHTSAIL_KNOWN_HOSTS`는 `ssh-keyscan -t ed25519 <호스트>` 출력 |
| 이미지 받기 | 워크플로의 `GITHUB_TOKEN` | 실행이 끝나면 만료. 서버에 로그인 정보를 남기지 않는다 |
| 앱·DB·OAuth·JWT 값 | 서버 `~/jandilog/.env` (600) | GitHub에 올리지 않는다. 배포 때 덮어쓰지 않는다 |
| 앱 컨테이너 | compose가 필요한 키만 전달 | 백업용 AWS 키 등은 앱에 노출되지 않는다 |
| 백업용 AWS 키 (`BACKUP_AWS_*`) | 서버 `.env` | 백업 전용 IAM 사용자. 아래 정책 |
| Atlas·Upstash·GitHub OAuth·S3 | 각 서비스 콘솔 | 값 발급·회전은 콘솔에서 |

- 값 교체: `.env` 수정 후 `docker compose -f docker-compose.prod.yml up -d app`. `JWT_SECRET`을 바꾸면 발급된 토큰이 모두 무효가 되어 재로그인한다.
- `DB_PASSWORD`는 MariaDB가 처음 만들어질 때만 반영된다. 이후 바꾸려면 DB에서 `ALTER USER`를 먼저 하고 `.env`를 맞춘다.
- 백업 IAM 정책(버킷·접두사만 바꿔 쓴다):
  ```json
  {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:PutObject","s3:AbortMultipartUpload"],"Resource":"arn:aws:s3:::<버킷>/mariadb/*"}]}
  ```
  버킷은 퍼블릭 액세스를 모두 막는다.
- 잔디 조회용 `GITHUB_API_TOKEN`(서버 전용 토큰)은 `.env`에 둔다. compose는 이미 앱에 전달한다.

## 메모리 (2GB 기준, DB명세서 5-1)

| 대상 | 설정 | 위치 |
|---|---|---|
| JVM | `-Xms256m -Xmx640m`, 메타스페이스 160m, 코드 캐시 64m, SerialGC, OOM이면 종료 후 재시작 | compose `JAVA_OPTS` |
| app 컨테이너 | 상한 960MB | compose `mem_limit` |
| MariaDB | 버퍼 풀 256M, 연결 50, 상한 512MB | `deploy/mariadb/jandilog.cnf`, compose |
| nginx | 상한 48MB | compose |
| OS·Docker | 약 300MB 남김 | |

MongoDB·Redis는 서버에 올리지 않는다. `jandilog.cnf`를 고치면 `docker compose -f docker-compose.prod.yml restart mariadb`로 직접 반영한다.

## 백업·복구

- `deploy/backup/mariadb-backup.sh`: 앱 계정으로 `mariadb-dump`(단일 트랜잭션) -> gzip -> 무결성 확인 -> S3 업로드. 일 1회 cron.
- 보관 기간·삭제 규칙은 기획서에 없어 만들지 않았다. 오래된 백업은 지우지 않는다.
- MongoDB는 Atlas가 백업한다.
- 복구 명령은 스크립트 머리 주석에 있다. 빈 MariaDB에 받아서 넣는다.
- 확인: `tail ~/jandilog/backup.log`, S3 콘솔에서 날짜별 파일.

## 배포와 롤백

- 흐름: CI 성공(main) -> 이미지 빌드·GHCR 푸시 -> 서버에 운영 파일 업로드 -> `deploy.sh`가 이미지 교체 -> 앱 healthy 확인 -> nginx 반영 -> HTTPS 헬스체크.
- 앱이 healthy가 되지 않으면 `deploy.sh`가 이전 이미지로 되돌리고 실패로 끝난다. 서버에는 `jandilog-app:current`와 `:previous`만 남는다.
- 수동 롤백: `docker tag jandilog-app:previous jandilog-app:current && docker compose -f docker-compose.prod.yml up -d --no-deps --force-recreate app`
- Flyway는 앞으로만 간다. 롤백은 앱 이미지만 되돌리며 스키마는 되돌리지 않는다. 운영 `clean`은 막혀 있다.
- 금지: `docker compose down -v`(DB 볼륨 삭제).
- 로그: `docker compose -f docker-compose.prod.yml logs -f app` (컨테이너별 10MB x 3개로 순환).
