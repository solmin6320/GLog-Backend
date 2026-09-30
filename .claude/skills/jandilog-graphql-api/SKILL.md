---
name: jandilog-graphql-api
description: 잔디로그 GraphQL API·인증·권한 구현 규칙. 스키마(.graphqls), 리졸버, @BatchMapping, JWT/OAuth 로그인, 이미지 업로드 REST, 에러 응답을 만들 때 사용한다.
---

# GraphQL API · 인증 (기능명세서 1·10장, 화면설계서 5-2, 5-4)

## 구조
- 엔드포인트 `/graphql` 하나. **이미지 업로드만 REST multipart**(프로필, S3).
- 스키마 파일 `src/main/resources/graphql/*.graphqls`, 도메인별 분리. **3주차 말 동결**: 이후 변경은 사용자 승인.
- 리졸버 `@Controller` + `@QueryMapping`/`@MutationMapping`. 목록의 작성자·댓글 수 등은 **`@BatchMapping`** 으로 N+1 방지.
- 페이지네이션 모양은 **Q-06 미결** → 목록 쿼리 만들기 전에 질문.

## 인증·권한
- GitHub OAuth2 → 백엔드 콜백 한 곳 → **일회용 코드(Redis 60초)** 발급 → 토큰 교환. 토큰을 URL에 싣지 않는다. 웹은 웹 주소, 앱은 딥링크(`jandilog://auth?code=`, 제안)로 복귀.
- 상태: `PENDING`(승인 대기, AU-03만 접근) / `ACTIVE` / `REJECTED`(토큰 미발급, E-02). 역할 `MEMBER`/`ADMIN`.
- 권한은 **`@PreAuthorize` 메서드 단위.** 팀장 전용(팀 관리·개인 면제 요청)은 팀 컨텍스트로 검사. 관리자라도 남의 팀 현황판·팀 관리 불가.
- CORS는 웹 도메인만. JWT 만료 → 인증 오류(E-03).

## 에러
- 예외는 전역 핸들러로 일관 변환. 500에 내부 정보 노출 금지(E-52). 403(E-09), 404(E-53).
- 사용자에게 보이는 문구는 화면설계서 5-4의 해요체 문구를 그대로 쓴다.

## 화면·기능 경계 (만들지 말 것)
전역 검색, 푸시·메일, 랭킹, 대댓글, 임시저장, 통계, 관리 로그 조회 API는 제외 범위.
게시판 검색은 제목·내용·태그만(2자 이상, 50자 이하: E-61, E-63).
