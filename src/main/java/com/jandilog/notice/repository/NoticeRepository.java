package com.jandilog.notice.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.notice.domain.Notice;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

	boolean existsBySystemTrue();

	// 시스템 공지 작성자로 쓸 관리자: 가장 먼저 가입한 활성 관리자
	@Query("""
			select m.id from Member m
			where m.role = com.jandilog.member.domain.MemberRole.ADMIN
			  and m.status = com.jandilog.member.domain.MemberStatus.ACTIVE
			order by m.id asc
			""")
	List<Long> findActiveAdminIds(Pageable pageable);

	// 고정 공지 우선, 그 안에서 최신순. 같은 시각이면 id가 큰 쪽이 먼저
	@Query("""
			select n from Notice n
			order by n.pinned desc, n.createdAt desc, n.id desc
			""")
	List<Notice> findFirstPage(Pageable pageable);

	// 키셋 페이징: 커서(고정 여부, 작성 시각, id)보다 뒤에 오는 공지
	@Query("""
			select n from Notice n
			where (:cursorPinned = true
			        and (n.pinned = false
			             or (n.pinned = true and (n.createdAt < :createdAt or (n.createdAt = :createdAt and n.id < :id)))))
			   or (:cursorPinned = false
			        and n.pinned = false and (n.createdAt < :createdAt or (n.createdAt = :createdAt and n.id < :id)))
			order by n.pinned desc, n.createdAt desc, n.id desc
			""")
	List<Notice> findPageAfter(@Param("cursorPinned") boolean cursorPinned,
			@Param("createdAt") LocalDateTime createdAt, @Param("id") long id, Pageable pageable);

}
