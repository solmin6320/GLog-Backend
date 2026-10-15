-- V3__post_index_team_fk.sql
-- post_index.team_id에 team FK 추가 (DB명세서 1-5). 팀 삭제는 team.deleted_at만 찍고 행을 남기므로 삭제 흐름을 막지 않는다

-- 존재하지 않는 팀을 가리키는 기존 연결을 끊는다 (FK 추가 전 정리)
UPDATE post_index
   SET team_id = NULL
 WHERE team_id IS NOT NULL
   AND team_id NOT IN (SELECT id FROM team);

ALTER TABLE post_index
  ADD CONSTRAINT fk_pi_team FOREIGN KEY (team_id) REFERENCES team(id);
