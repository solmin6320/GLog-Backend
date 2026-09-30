---
name: jandilog-devops
description: 잔디로그 Docker Compose, Dockerfile, GitHub Actions CI/CD, Lightsail·HTTPS·백업 구성 규칙. 인프라 파일과 배포 파이프라인을 만들 때 사용한다.
---

# DevOps (기능명세서 10장, DB명세서 5장)

## 배치
- 백엔드: AWS Lightsail 2GB, Docker. MariaDB는 같은 서버 컨테이너. **MongoDB는 Atlas M0**, **Redis는 Upstash**(서버에 올리지 않음, 메모리 부족 방지).
- 이미지: S3. CI/CD: GitHub Actions(빌드 → Docker 이미지 → Lightsail 배포).
- HTTPS: 도메인 + Nginx(또는 Caddy) + Let's Encrypt. 웹 CORS는 웹 도메인만.

## 로컬
- Docker Compose로 **MariaDB·Redis·MongoDB** 통일(3명 환경 차이 방지). `docker-compose.yml`은 리포 루트.
- 접속 정보는 `.env`(커밋 금지) + `.env.example`(커밋).

## 설정
- JVM `-Duser.timezone=Asia/Seoul`, 스케줄러 zone `Asia/Seoul`.
- 메모리: JVM 힙은 512~768MB 안쪽(`-Xmx`), 컨테이너 메모리 제한 명시.
- Actuator 헬스체크에서 Redis 제외. 기타 actuator 엔드포인트는 외부 비노출.
- Flyway는 앱 기동 시 자동 적용. 운영 `clean` 금지.

## 백업
- **1주차에 함께**: MariaDB `mysqldump` 일 1회 → S3 (크론). MongoDB 백업은 Atlas가 맡는다(10장). 별도 백업을 추가하지 않는다.

## CI
- PR/푸시 시 `./gradlew build test`. 시크릿은 GitHub Secrets. 워크플로에 비밀값 하드코딩 금지.
- 1주차 목표: 배포 파이프라인 먼저 완성("배포 먼저").

## 질문 대상
도메인·Lightsail 계정·S3 버킷·Upstash/Atlas 계정 정보가 필요하면 사용자에게 묻는다. 기획서에 없는 도구 도입은 하지 않는다.
