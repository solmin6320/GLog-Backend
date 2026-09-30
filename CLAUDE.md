# 잔디로그 백엔드 (GLog-Backend)

백엔드 전용 프로젝트. 리포: https://github.com/solmin6320/GLog-Backend
스택: Java 21 · Gradle(wrapper) · Spring Boot 3.x · Spring for GraphQL · Spring Security(JWT) · JPA · MariaDB(Flyway) · MongoDB(Atlas) · Redis(Upstash).

## 철칙

1. 모든 대화·질문·답변·주석·커밋 메시지는 **한국어**, 파일은 **UTF-8**.
2. 기획서(`백엔드 기획서/`)가 정본. 구현 전에 해당 장을 먼저 읽는다. 충돌 시 **기능명세서 우선**.
3. **기획서에 없는 기능은 개발하지 않는다.** 기능 추가·변경·삭제 금지. 명세에 없는 규칙이 필요해 보이면 구현하지 말고 사용자에게 묻는다.
4. **중요한 결정은 사용자에게 먼저 질문**한다. 미결 Q-01~Q-13(전체문서 5-4-10)에 걸리면 임의로 정하지 않는다.
5. **기획서에 없는 의존성은 추가하지 않는다.** 기획서 기술 스택(10장·0장 공통 스킬) 범위의 라이브러리만 쓴다. 그 밖이 꼭 필요하면 추가하지 말고 사용자에게 먼저 묻는다.
6. `README.md`는 건드리지 않는다.
7. **Git: 커밋 + 푸시만.** PR은 사용자가 직접 연다. **main 직접 푸시 절대 금지.** 브랜치 `feature/담당-기능`(수정은 `fix/`, 테스트 `test/`, 인프라 `chore/`).
8. 코드 주석은 짧게, **무엇을 했는지**만. AI 말투(“~합니다”, “다음과 같이” 등) 금지.

## 에이전트 팀 (12명, 모델 sonnet 5.5 · effort xhigh 고정)

| 역할 | 인원 | 정의 파일 |
|---|---|---|
| 보안 | 2 | `.claude/agents/sec-*.md` |
| 기능 개발 | 4 | `.claude/agents/dev-*.md` |
| 테스트(단위+통합) | 4 | `.claude/agents/test-*.md` |
| DevOps | 2 | `.claude/agents/ops-*.md` |

모델·effort를 바꾸려면 각 파일 frontmatter의 `model`, `effort`만 수정한다. 팀 회의는 사용자가 요청할 때 진행하며, 안건·결정·보류를 한국어로 짧게 정리한다.

## 스킬

- 공통: `jandilog-rules`(코딩·Git 규칙), `jandilog-spec-first`(명세 조회·질문 절차)
- 전문: `jandilog-graphql-api`, `jandilog-data-layer`, `jandilog-judgment-warning`, `jandilog-testing`, `jandilog-devops`

## 확정된 결정 (2026-10-01, 사용자가 팀에 위임)

| Q | 결정 | 근거 |
|---|---|---|
| Q-06 페이지네이션 | **커서 기반, 페이지당 20개** | 화면설계서 E-57 제안. Mongo `_id` 커서와 맞음 |
| Q-01 초대코드 | **평문 저장**, 팀장이 TM-06에서 다시 확인 가능 | 재발급 불가라 해시로 두면 코드를 잊은 팀이 코드 초대를 영영 못 씀 |

나머지 Q는 해당 코드를 만들기 직전에 같은 방식(근거 제시 후 결정 또는 질문)으로 정하고 이 표에 추가한다.
