-- V5__admin_action_log_promote_admin.sql
-- 관리자가 활성 회원을 관리자로 지정한 행동을 admin_action_log에 남기기 위해 action 허용 값 끝에 PROMOTE_ADMIN을 추가한다.
-- 기존 값의 이름과 순서는 그대로 두고 끝에만 붙인다

ALTER TABLE admin_action_log DROP CONSTRAINT ck_aal_action;
ALTER TABLE admin_action_log
  ADD CONSTRAINT ck_aal_action CHECK (action IN ('REJECT_MEMBER','DELETE_POST','DELETE_COMMENT',
                                                 'FULFILL_PENALTY','CORRECT_JUDGMENT','RESTORE_WARNING',
                                                 'KICK_MEMBER','DELETE_TEAM','PROMOTE_ADMIN'));
