---
name: jandilog-devops
description: 잔디로그 Docker Compose, Dockerfile, GitHub Actions CI, AWS(EC2·RDS·ElastiCache)·HTTPS·백업 구성 규칙. 인프라 파일과 배포 설정을 만질 때 사용한다.
---

# DevOps (기능명세서 10장, DB명세서 5장)

## 배치
- AWS 서울 리전(ap-northeast-2)으로 통일. 백엔드: **EC2**(public subnet), Docker. 앱 서버에는 JVM만 올리고 DB는 모두 관리형.
- 관계형 DB: **RDS PostgreSQL**(`DB_SSL_MODE=require` 이상). Redis: **ElastiCache Valkey 8.2**(Redis 호환, 전송 중 암호화 + AUTH 토큰, `REDIS_SSL=true`). MongoDB: **Atlas M0**(AWS 서울).
- 보안 그룹: EC2는 SSH(관리자 IP만)·80·443만 연다. RDS·ElastiCache는 EC2 보안 그룹에서 오는 접속만 받는다.
- 이미지: S3. CI: GitHub Actions(빌드·테스트만). 배포 자동화(CD)는 없다.
- HTTPS: 도메인 + Caddy(인증서 자동 발급). 웹 CORS는 웹 도메인만.

## 로컬
- Docker Compose로 **PostgreSQL 16·Redis·MongoDB** 통일(3명 환경 차이 방지). `docker-compose.yml`은 리포 루트.
- 접속 정보는 `.env`(커밋 금지) + `.env.example`(커밋).

## 설정
- JVM `-Duser.timezone=Asia/Seoul`, 스케줄러 zone `Asia/Seoul`.
- 메모리: JVM 힙은 512~768MB 안쪽(`-Xmx`), 컨테이너 메모리 제한 명시.
- Actuator 헬스체크에서 Redis 제외. 기타 actuator 엔드포인트는 외부 비노출.
- Flyway는 앱 기동 시 자동 적용. 운영 `clean` 금지.

## 백업
- PostgreSQL은 RDS 백업 설정을 따른다. MongoDB 백업은 Atlas가 맡는다(10장, DB명세서 5-2). 별도 덤프 작업을 추가하지 않는다.

## CI
- PR/푸시 시 `docker compose up -d --wait`로 DB 3종을 띄우고 `./gradlew build`(테스트 포함). 워크플로에 비밀값 하드코딩 금지.
- 배포 자동화(CD)를 새로 만드는 것은 기획서에 없으므로 필요하면 먼저 묻는다.

## 질문 대상
도메인·AWS 계정(EC2·RDS·ElastiCache)·S3 버킷·Atlas 계정 정보가 필요하면 사용자에게 묻는다. 기획서에 없는 도구 도입은 하지 않는다.
