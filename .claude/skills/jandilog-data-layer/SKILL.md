---
name: jandilog-data-layer
description: 잔디로그 DB 3종(MariaDB·MongoDB·Redis) 경계, Flyway, 엔티티·컬렉션·키 규칙. 엔티티, 마이그레이션, 리포지토리, Redis 키를 만질 때 사용한다.
---

# 데이터 계층 (DB명세서 0~4장)

## 분담
| DB | 담당 | 기준 |
|---|---|---|
| MariaDB | 회원·팀·판정·경고·벌칙·면제·공지·`post_index` | 틀리면 불이익 → 트랜잭션·제약 |
| MongoDB | `posts`, `comments` | 글 종류별 항목이 다름 |
| Redis | 잔디 캐시·추방 차단·일회용 코드 | TTL이 규칙 |

## MariaDB
- `utf8mb4`, ID `BIGINT AUTO_INCREMENT`, 시간 `DATETIME`(앱이 KST 값 입력), 주차 = 월요일 `DATE`.
- 스키마는 **Flyway만**. `V1__init.sql`, `V2__rest.sql` 초안은 DB명세서 부록. 적용된 마이그레이션 수정 금지, 새 버전으로 추가.
- 경고·팀·회원 상태는 소프트 삭제(`deleted_at`). 경고 이력은 지우지 않는다.
- `member.github_id`(숫자)가 식별자, `github_login`은 로그인마다 갱신.
- 조건부 UNIQUE는 PERSISTENT 생성 컬럼으로(MariaDB는 부분 인덱스 없음).

## MongoDB
- `posts.sections`는 타입별: TROUBLESHOOTING(problem/cause/solution), DEVLOG(did/learned). `isRecord`, `writtenDate` 저장.
- 인덱스: `{type,createdAt}`, `{tags,createdAt}`, `{authorId,writtenDate}`, `{teamId,createdAt}`, text(`title`, `sections.$**`). 웹 헤더 검색도 같은 text 인덱스.
- `comments`는 별도 컬렉션, `parentId` 없음(대댓글 없음). 댓글엔 `teamId` 복제 금지(글 경유 조회, 4-6).

## 경계 쓰기 순서
- 글 작성(4-1): **Mongo 먼저 → `post_index` 나중.** 분산 트랜잭션 쓰지 않는다. 실패 시 색인 누락 가능. 보정 배치는 (제안)일 뿐이므로 만들기 전에 질문.
- 팀 삭제(4-4): `team.deleted_at` + Mongo `teamId=null` + `post_index.team_id=NULL` + `warning_team.removed_at` 모두.
- 프로필 글·댓글 수는 Mongo `countDocuments`(`post_index`로 세지 않는다, 4-5).

## Redis 키
`grass:{memberId}:{yyyy-MM-dd}` 26h · `ban:{teamId}:{memberId}` 1년 · `authcode:{code}` 60s. 재계산 락은 Redis 키 없이 MariaDB 회원 행 비관적 락(Q-08).
- 일회용 코드는 사용 즉시 삭제. 추방 차단은 초대코드 참가에만 적용(아이디 초대 허용).
- Actuator 헬스체크에서 Redis 제외.

## 확정 (CLAUDE.md 결정 표)
Q-01 초대코드 평문 저장 · Q-06 커서 기반 20개 `{items, nextCursor}` · Q-08 MariaDB 회원 행 비관적 락.
