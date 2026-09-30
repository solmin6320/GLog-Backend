-- V2__rest.sql  (외래키 의존이 얕아 V1 뒤 어디에 두어도 된다)
CREATE TABLE team_invitation (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id      BIGINT   NOT NULL,
  invitee_id   BIGINT   NOT NULL,
  status       ENUM('PENDING','ACCEPTED','DECLINED') NOT NULL DEFAULT 'PENDING',
  created_at   DATETIME NOT NULL,
  responded_at DATETIME NULL,
  INDEX idx_ti_invitee (invitee_id, status),
  CONSTRAINT fk_ti_team    FOREIGN KEY (team_id)    REFERENCES team(id),
  CONSTRAINT fk_ti_invitee FOREIGN KEY (invitee_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE exemption_period (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  week_start DATE         NOT NULL UNIQUE,
  reason     VARCHAR(200) NOT NULL,
  created_by BIGINT       NOT NULL,
  created_at DATETIME     NOT NULL,
  updated_at DATETIME     NULL,
  CONSTRAINT fk_ep_admin FOREIGN KEY (created_by) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE personal_exemption (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id     BIGINT       NOT NULL,
  requested_by  BIGINT       NOT NULL,
  week_start    DATE         NOT NULL,
  status        ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  reason        VARCHAR(500) NOT NULL,
  reject_reason VARCHAR(500) NULL,
  responded_at  DATETIME     NULL,
  created_at    DATETIME     NOT NULL,
  INDEX idx_pe_pending (status, week_start),
  INDEX idx_pe_member  (member_id, week_start),
  CONSTRAINT fk_pe_member    FOREIGN KEY (member_id)    REFERENCES member(id),
  CONSTRAINT fk_pe_requester FOREIGN KEY (requested_by) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judgment_correction (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  judgment_id   BIGINT       NOT NULL,
  before_status ENUM('PASS','FAIL','EXEMPT','EXCLUDED','HOLD') NOT NULL,
  after_status  ENUM('PASS','FAIL','EXEMPT')                   NOT NULL,
  reason        VARCHAR(500) NOT NULL,
  admin_id      BIGINT       NOT NULL,
  created_at    DATETIME     NOT NULL,
  INDEX idx_jc_judgment (judgment_id),
  CONSTRAINT fk_jc_judgment FOREIGN KEY (judgment_id) REFERENCES weekly_judgment(id),
  CONSTRAINT fk_jc_admin    FOREIGN KEY (admin_id)    REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notice (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  title      VARCHAR(200) NOT NULL,
  content    TEXT         NOT NULL,
  is_pinned  BOOLEAN      NOT NULL DEFAULT FALSE,
  is_system  BOOLEAN      NOT NULL DEFAULT FALSE,
  author_id  BIGINT       NOT NULL,
  created_at DATETIME     NOT NULL,
  updated_at DATETIME     NULL,
  INDEX idx_notice_list (is_pinned DESC, created_at DESC),
  CONSTRAINT fk_notice_author FOREIGN KEY (author_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_action_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id    BIGINT      NOT NULL,
  action      ENUM('REJECT_MEMBER','DELETE_POST','DELETE_COMMENT',
                   'FULFILL_PENALTY','CORRECT_JUDGMENT','RESTORE_WARNING') NOT NULL,
  target_type VARCHAR(30) NOT NULL,
  target_id   VARCHAR(40) NOT NULL,
  reason      VARCHAR(500) NULL,
  created_at  DATETIME    NOT NULL,
  INDEX idx_aal_admin (admin_id, created_at),
  CONSTRAINT fk_aal_admin FOREIGN KEY (admin_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
