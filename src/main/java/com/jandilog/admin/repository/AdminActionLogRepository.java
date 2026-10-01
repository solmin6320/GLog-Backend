package com.jandilog.admin.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.admin.domain.AdminActionLog;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {
}
