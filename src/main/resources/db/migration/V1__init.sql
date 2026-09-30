-- V1__init.sql
CREATE TABLE member (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  github_id            BIGINT       NOT NULL UNIQUE,
  github_login         VARCHAR(39)  NOT NULL,
  nickname             VARCHAR(30)  NOT NULL,
  profile_image_url    VARCHAR(512) NULL,
  status               ENUM('PENDING','ACTIVE','REJECTED') NOT NULL DEFAULT 'PENDING',
  role                 ENUM('MEMBER','ADMIN')              NOT NULL DEFAULT 'MEMBER',
  first_team_joined_at DATETIME     NULL,
  created_at           DATETIME     NOT NULL,
  approved_at          DATETIME     NULL,
  INDEX idx_member_login (github_login)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(50)  NOT NULL,
  description VARCHAR(200) NULL,
  leader_id   BIGINT       NOT NULL,
  invite_code VARCHAR(255) NOT NULL UNIQUE,
  is_public   BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at  DATETIME     NOT NULL,
  deleted_at  DATETIME     NULL,
  CONSTRAINT fk_team_leader FOREIGN KEY (leader_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_member (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id    BIGINT   NOT NULL,
  member_id  BIGINT   NOT NULL,
  joined_at  DATETIME NOT NULL,
  left_at    DATETIME NULL,
  leave_type ENUM('SELF','KICKED','TEAM_DELETED') NULL,
  joined_by  ENUM('INVITE_CODE','INVITATION')     NOT NULL,
  used_invite_code VARCHAR(255) NULL,
  UNIQUE KEY uk_team_member (team_id, member_id, joined_at),
  INDEX idx_member_current (member_id, left_at),
  CONSTRAINT fk_tm_team   FOREIGN KEY (team_id)   REFERENCES team(id),
  CONSTRAINT fk_tm_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_index (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  mongo_post_id CHAR(24) NOT NULL UNIQUE,
  author_id     BIGINT   NOT NULL,
  team_id       BIGINT   NULL,
  post_type     ENUM('TROUBLESHOOTING','DEVLOG') NOT NULL,
  is_record     BOOLEAN  NOT NULL,
  written_date  DATE     NOT NULL,
  created_at    DATETIME NOT NULL,
  deleted_at    DATETIME NULL,
  INDEX idx_post_judge (author_id, written_date, is_record),
  CONSTRAINT fk_pi_author FOREIGN KEY (author_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weekly_judgment (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id      BIGINT NOT NULL,
  week_start     DATE   NOT NULL,
  status         ENUM('PASS','FAIL','EXEMPT','EXCLUDED','HOLD') NOT NULL,
  skip_reason    ENUM('NO_TEAM','FIRST_WEEK','EXEMPTION_PERIOD','PERSONAL_EXEMPTION') NULL,
  hold_reason    ENUM('API_ERROR','IDENTITY_MISMATCH') NULL,
  retry_count    INT     NOT NULL DEFAULT 0,
  verified_days  INT     NULL,
  record_count   INT     NULL,
  corrected      BOOLEAN NOT NULL DEFAULT FALSE,
  judged_at      DATETIME NULL,
  UNIQUE KEY uk_judgment (member_id, week_start),
  INDEX idx_judgment_status (status, week_start),
  CONSTRAINT fk_wj_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judgment_team (
  judgment_id BIGINT NOT NULL,
  team_id     BIGINT NOT NULL,
  PRIMARY KEY (judgment_id, team_id),
  CONSTRAINT fk_jt_judgment FOREIGN KEY (judgment_id) REFERENCES weekly_judgment(id),
  CONSTRAINT fk_jt_team     FOREIGN KEY (team_id)     REFERENCES team(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judgment_day (
  judgment_id BIGINT  NOT NULL,
  day         DATE    NOT NULL,
  has_grass   BOOLEAN NOT NULL,
  has_record  BOOLEAN NOT NULL,
  PRIMARY KEY (judgment_id, day),
  CONSTRAINT fk_jd_judgment FOREIGN KEY (judgment_id) REFERENCES weekly_judgment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE warning (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id     BIGINT   NOT NULL,
  week_start    DATE     NOT NULL,
  deleted_at    DATETIME NULL,
  delete_reason ENUM('KICKED','TEAM_DELETED') NULL,
  created_at    DATETIME NOT NULL,
  UNIQUE KEY uk_warning (member_id, week_start),
  INDEX idx_warning_alive (member_id, deleted_at),
  CONSTRAINT fk_w_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE warning_team (
  warning_id BIGINT   NOT NULL,
  team_id    BIGINT   NOT NULL,
  removed_at DATETIME NULL,
  PRIMARY KEY (warning_id, team_id),
  CONSTRAINT fk_wt_warning FOREIGN KEY (warning_id) REFERENCES warning(id),
  CONSTRAINT fk_wt_team    FOREIGN KEY (team_id)    REFERENCES team(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE penalty_fulfillment (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id    BIGINT   NOT NULL,
  fulfilled_at DATETIME NOT NULL,
  admin_id     BIGINT   NOT NULL,
  reached_week DATE     NOT NULL,
  INDEX idx_pf_member (member_id, fulfilled_at),
  CONSTRAINT fk_pf_member FOREIGN KEY (member_id) REFERENCES member(id),
  CONSTRAINT fk_pf_admin  FOREIGN KEY (admin_id)  REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
