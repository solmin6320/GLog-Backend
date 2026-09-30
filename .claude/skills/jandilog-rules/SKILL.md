---
name: jandilog-rules
description: 잔디로그 백엔드 공통 규칙. 코드를 쓰거나 커밋·푸시하기 전에 항상 적용한다 (주석, 한국어, 브랜치, 커밋, 패키지, 시간대).
---

# 공통 규칙 1 — 코딩·Git

## 언어·인코딩
- 대화, 주석, 커밋, 이슈 전부 한국어. 파일 UTF-8.
- 주석은 짧게. **무엇을 했는지** 한 줄. 예: `// 주 종료 시각 기준 소속 스냅샷 저장`
- AI 말투 금지: "~해드리겠습니다", "다음과 같이", "확인해 보세요", 이모지, 장황한 Javadoc.
- 자명한 코드엔 주석을 달지 않는다. 이유가 비자명한 곳(규칙의 근거)만 E-번호·장 번호로 짧게.

## 코드
- Java 21, Spring Boot 3.x. 패키지: `com.jandilog.<도메인>.{domain,repository,service,graphql,dto}` (member, team, post, notice, judgment, warning, exemption, profile, admin, common). 공통 설정은 `common.config`.
- 시간: JVM·스케줄러 모두 `Asia/Seoul`. 주차는 **월요일 `LocalDate`** 로 식별.
- 경고·판정 같은 불이익 데이터는 MariaDB 트랜잭션 안에서만 변경.
- 권한은 `@PreAuthorize` 메서드 단위. URL 권한에 기대지 않는다.
- **기획서에 없는 기능·동작을 개발하지 않는다.** 필요해 보이면 멈추고 질문.

## Git
- **main 직접 푸시 금지.** 작업 전 `git branch --show-current`로 확인.
- 브랜치: `feature/<담당>-<기능>` (예: `feature/team-invite`), 수정 `fix/`, 테스트 `test/`, 인프라 `chore/`.
- 커밋: `feat|fix|test|chore|docs: 한국어 요약` (72자 이내). **기능 단위로 1번씩**, 그 기능의 테스트도 **기능 단위로 1번** (기능 커밋과 테스트 커밋은 분리). 여러 기능을 한 커밋에 섞거나 한 기능을 잘게 쪼개지 않는다.
- **커밋 + 푸시까지만.** PR 생성 금지(사용자가 직접). `--force`, `--no-verify` 금지.
- `README.md`는 수정하지 않는다.
- 비밀값(.env, 키, 토큰) 커밋 금지.

## 의존성
- **기획서 기술 스택에 없는 의존성은 추가하지 않는다.** 허용 범위: Spring Boot, Spring for GraphQL, Spring Security(OAuth2 Client·JWT), Spring Data JPA, Spring Data MongoDB, Spring Data Redis, Flyway, MariaDB 드라이버, Spring Scheduler, AWS SDK for Java(S3), JUnit, (선택) QueryDSL.
- 위 밖이 필요하면 `build.gradle`을 건드리지 말고 사용자에게 이유와 함께 묻는다.
