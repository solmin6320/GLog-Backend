---
name: jandilog-judgment-warning
description: 잔디로그 주간 판정·경고·면제·정정·재계산 구현 규칙 요약. judgment, warning, exemption, 벌칙, 스케줄러 코드를 만들거나 테스트할 때 사용한다.
---

# 판정·경고 (기능명세서 5~7장, DB 1-6~1-14, 4-2·4-3)

## 판정
- 개인 단위, 여러 팀이어도 1회. 기간 월 00:00~일 23:59 KST. 월요일 오전 자동(잔디 캐시 갱신 **이후**).
- 통과 = 인증일 ≥ 3 **AND** 기록글 ≥ 1. 인증일 = `has_grass OR has_record`인 날 수(같은 날 둘 다여도 1).
- 제외(`EXCLUDED`): NO_TEAM, FIRST_WEEK(`member.first_team_joined_at`, 생애 1회). 면제(`EXEMPT`): EXEMPTION_PERIOD, PERSONAL_EXEMPTION. 둘이 겹치면 독립 보존(E-44).
- 소속은 주 종료 시각 스냅샷 → `judgment_team`. 일자별 근거 7행 → `judgment_day`.
- 판정은 **MariaDB + Redis 잔디 캐시만** 읽는다(Mongo 접근 금지). `post_index.is_record`, `written_date` 사용.
- GitHub 조회 실패 → 그 회원만 `HOLD`, 경고 없음. 사유 `API_ERROR`(재시도) / `IDENTITY_MISMATCH`(재로그인).
- 재실행은 HOLD만. `(member_id, week_start)` UNIQUE 기반 멱등(E-35). 확정 상태는 skip.
- 확정 판정은 글 삭제로 불변. 바뀌는 길은 관리자 정정(PASS/FAIL/EXEMPT만, HOLD·EXCLUDED 정정 불가)뿐.

## 경고
- 미달 시 사람당 1개(`UNIQUE(member_id, week_start)`), 판정 시점 소속 팀 전부를 `warning_team`에.
- 2주 연속 통과 → 가장 오래된 **살아있는** 경고 1개 차감, 연속 0. 3회 도달 시 차감 중지.
- 벌칙 이행(`penalty_fulfillment`) → 경고 0. **되돌릴 수 없는 고정점.** 이전 주차 정정·복구는 기록만 고치고 카운트 미반영(E-58, E-59).
- 추방·팀삭제 → 그 팀 `warning_team.removed_at`, 0개 남으면 경고 소프트 삭제(`KICKED`/`TEAM_DELETED`). 자진 탈퇴는 유지.
- 삭제된 팀 경고도 복구 가능("삭제된 팀" 표시, E-43).

## 재계산 (가장 틀리기 쉬움)
```
회원 단위 락 → 최근 fulfilled_at(기준선) → deleted_at IS NULL 경고만 week_start 오름차순
→ 기준선 이전 제외 → EXEMPT·EXCLUDED 주는 끊지도 늘리지도 않고 건너뜀 → 처음부터 다시 계산
```
- **증감(+1/-1) 금지.** 저장된 카운트를 더하고 빼지 않고 전부 덮어쓴다(`member_warning_state` 류 캐시 포함).
- 세는 단위는 "미달 주차"가 아니라 "살아 있는 경고 레코드"(E-60).
- 정정·소급 면제는 **미리보기(무변경) → 확인 → 반영**. 미리보기는 DB를 바꾸지 않는다.
- 되돌릴 수 없는 관리자 행동은 `admin_action_log`에 기록(조회 API 만들지 않음).

## 미결 (임의 결정 금지, 질문)
Q-02 재초대 복원 · Q-03 HOLD와 연속 · Q-04 소속 기준 시점 · Q-07 재시도 횟수 · Q-08 락 위치 · Q-10 경고 경계 4건 · Q-11 기록글 날짜 귀속.
