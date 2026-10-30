# GLog (잔디로그) — Backend

> GitHub 잔디와 기록글로 매주 개발 습관을 인증하는 팀 스터디 서비스, **잔디로그**의 백엔드 서버입니다.

## 소개

잔디로그는 "꾸준히 코딩하고, 배운 것을 기록하는" 습관을 팀 단위로 지켜 나가기 위한 서비스입니다.

- 회원은 **GitHub 계정으로 로그인**하고 팀에 참가합니다.
- 매일의 **GitHub 잔디(커밋 기여)** 와 서비스에 쓴 **기록글(트러블슈팅·개발일지)** 이 그날의 인증이 됩니다.
- 매주 월요일 아침, 서버가 지난주 활동을 **자동으로 판정**합니다.
- 기준을 채우지 못하면 **경고**가 쌓이고, 꾸준히 통과하면 경고가 줄어듭니다. 경고가 3개가 되면 **벌칙 대상**이 됩니다.

팀원끼리 서로의 주간 현황을 보며 자극을 주고받고, 기록글 게시판에서 문제 해결 경험과 배운 내용을 나누는 것이 목표입니다.

## 핵심 규칙

| 구분 | 내용 |
|---|---|
| 판정 기간 | 월요일 00:00 ~ 일요일 23:59 (KST) |
| 인증일 | 잔디가 있거나 기록글을 쓴 날 (같은 날 둘 다 있어도 1일) |
| 통과 기준 | 인증일 **3일 이상** 그리고 기록글 **1개 이상** |
| 판정 결과 | 통과(PASS) · 미달(FAIL) · 면제(EXEMPT) · 제외(EXCLUDED) · 보류(HOLD) |
| 경고 | 미달한 주마다 1개 |
| 경고 차감 | 2주 연속 통과하면 가장 오래된 경고 1개 차감 |
| 벌칙 | 경고 3개면 벌칙 대상, 벌칙을 이행하면 경고 0개로 초기화 |
| 제외·면제 | 팀이 없는 주·첫 참가 주는 제외, 관리자가 정한 면제 기간·승인된 개인 면제는 면제 |

판정은 개인 단위로 한 주에 한 번이며, 여러 팀에 속해 있어도 한 번만 판정합니다. GitHub 조회에 실패한 회원은 경고 없이 보류되고, 10분 간격으로 재시도합니다.

## 주요 기능

**인증·회원**
- GitHub OAuth 로그인, JWT 액세스 토큰 발급 (웹·앱 딥링크 지원)
- 첫 로그인 즉시 활성 회원으로 가입
- 관리자 지정(`ADMIN_GITHUB_IDS` 환경변수 또는 기존 관리자의 승격)

**팀**
- 팀 생성·수정·삭제, 공개/비공개 설정
- 초대코드로 참가, GitHub 아이디로 초대·수락·거절·철회
- 팀원 추방, 탈퇴, 팀장 위임
- 팀 현황판: 팀원별 이번 주 인증일·기록글·경고 현황, 팀의 최근 기록글

**기록글 게시판**
- 트러블슈팅(문제·원인·해결)과 개발일지(한 일·배운 점) 두 종류
- 태그, 관련 커밋 링크, 팀 연결, 검색, 인기 태그
- 댓글, 팀장의 팀 글 관리

**주간 활동·판정**
- 내 주간 활동: 날짜별 잔디·기록글, 통과 예상 여부
- 판정 이력, 경고 이력·벌칙 현황
- 프로필: 1년 잔디, 팀별 경고, 판정 이력

**관리자**
- 대시보드, 판정 목록·보류 재시도, 판정 정정(미리보기 후 반영)
- 면제 기간 등록, 개인 면제 요청 승인·거절(소급 적용 미리보기)
- 경고 복구, 벌칙 이행 처리, 게시물 관리, 공지사항, 회원 관리

## 기술 스택

| 분류 | 사용 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5, Spring for GraphQL, Spring Security (OAuth2 Client, Resource Server / JWT) |
| Data | Spring Data JPA + MariaDB (Flyway), Spring Data MongoDB, Spring Data Redis |
| Build | Gradle (wrapper) |
| Infra | Docker, AWS Lightsail, MongoDB Atlas, Upstash Redis |
| CI | GitHub Actions |

### 저장소 역할

| 저장소 | 용도 |
|---|---|
| MariaDB | 회원, 팀, 판정, 경고, 면제, 글 색인 등 정합성이 중요한 데이터 |
| MongoDB | 기록글 본문, 댓글 |
| Redis | GitHub 잔디 캐시, 로그인 일회용 코드, 추방 차단 키 |

### 정기 작업 (KST)

| 작업 | 주기 |
|---|---|
| 잔디 캐시 갱신 | 매일 06:00 |
| 주간 판정 | 매주 월요일 07:00 |
| 보류 판정 재시도 | 10분마다 |

서버가 꺼져 있어 빠진 주차는 기동 시 자동으로 보충 판정합니다.

## API

모든 API는 **GraphQL** 단일 엔드포인트(`/graphql`)로 제공합니다. 스키마는 [`src/main/resources/graphql`](src/main/resources/graphql)에 도메인별로 나뉘어 있습니다.

- 목록 조회는 커서 기반 페이지네이션(20개, `{ items, nextCursor }`)
- 루트 필드는 기본 거부, 공개는 `exchangeAuthCode`만, 나머지는 로그인한 활성 회원만 사용
- 쿼리 깊이 10, 복잡도 50 제한

## 프로젝트 구조

```
src/main/java/com/jandilog
├── admin       # 관리자 대시보드·정정·보류·게시물·경고 복구
├── common      # 설정, 보안(JWT·OAuth), 예외, 페이지네이션
├── exemption   # 면제 기간, 개인 면제, 판정 정정
├── judgment    # 잔디 조회, 주간 판정, 스케줄러
├── member      # 로그인, 회원, 가입 승인·관리자 지정
├── notice      # 공지사항
├── post        # 기록글, 댓글
├── profile     # 프로필, 판정 이력
├── team        # 팀, 초대, 추방, 팀 현황판
└── warning     # 경고, 재계산, 벌칙 이행
```

## 로컬 실행

**요구 사항:** JDK 21, Docker

1. 환경변수 파일을 만들고 값을 채웁니다. GitHub OAuth App(`GITHUB_CLIENT_ID`·`GITHUB_CLIENT_SECRET`), 잔디 조회용 토큰(`GITHUB_API_TOKEN`), `JWT_SECRET`은 필수입니다. 각 항목 설명은 [`.env.example`](.env.example)에 있습니다.

   ```bash
   cp .env.example .env
   ```

2. 로컬 DB(MariaDB·MongoDB·Redis)를 띄웁니다.

   ```bash
   docker compose up -d --wait
   ```

3. 서버를 실행합니다. 기본 주소는 `http://localhost:8080`입니다.

   ```bash
   ./gradlew bootRun
   ```

### 테스트

테스트는 로컬 DB 3종에 붙으므로 `docker compose up -d --wait` 후 실행합니다.

```bash
./gradlew test
```

## 배포

- `Dockerfile`로 실행 가능한 `app.jar` 이미지를 만들어 AWS Lightsail에서 실행합니다.
- 운영 프로필(`SPRING_PROFILES_ACTIVE=prod`)은 기본값 없이 모든 접속 정보를 환경변수로 받으며, 값이 빠지거나 안전하지 않으면 기동을 막습니다.
- GitHub Actions가 push·PR마다 DB 3종을 띄워 빌드와 테스트를 실행합니다.
