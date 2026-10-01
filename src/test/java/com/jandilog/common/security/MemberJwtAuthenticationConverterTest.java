package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.repository.MemberRepository;

// 권한은 토큰이 아니라 요청 시점의 DB 상태·역할로 정한다 (E-01, E-02, E-09)
@ExtendWith(MockitoExtension.class)
class MemberJwtAuthenticationConverterTest {

	@Mock
	private MemberRepository memberRepository;

	private MemberJwtAuthenticationConverter converter;

	@BeforeEach
	void setUp() {
		converter = new MemberJwtAuthenticationConverter(memberRepository);
	}

	private Member member(long id, MemberStatus status, MemberRole role) {
		Member member = Member.createPending(1000 + id, "login" + id, "nick" + id, LocalDateTime.of(2026, 1, 1, 0, 0));
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "status", status);
		ReflectionTestUtils.setField(member, "role", role);
		return member;
	}

	private Jwt jwt(String subject) {
		Jwt.Builder builder = Jwt.withTokenValue("token")
				.header("alg", "HS256")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600));
		if (subject != null) {
			builder.subject(subject);
		}
		return builder.build();
	}

	private List<String> authoritiesOf(MemberStatus status, MemberRole role) {
		when(memberRepository.findById(42L)).thenReturn(Optional.of(member(42L, status, role)));
		return converter.convert(jwt("42")).getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
	}

	@Test
	void 활성_일반_회원은_MEMBER만_받는다() {
		assertThat(authoritiesOf(MemberStatus.ACTIVE, MemberRole.MEMBER)).containsExactly("ROLE_MEMBER");
	}

	@Test
	void 활성_관리자는_MEMBER와_ADMIN을_받는다() {
		assertThat(authoritiesOf(MemberStatus.ACTIVE, MemberRole.ADMIN)).containsExactly("ROLE_MEMBER", "ROLE_ADMIN");
	}

	@Test
	void 승인_대기는_PENDING만_받고_관리자_역할이어도_ADMIN을_받지_못한다() {
		assertThat(authoritiesOf(MemberStatus.PENDING, MemberRole.MEMBER)).containsExactly("ROLE_PENDING");
		assertThat(authoritiesOf(MemberStatus.PENDING, MemberRole.ADMIN)).containsExactly("ROLE_PENDING");
	}

	@Test
	void 거절된_회원은_REJECTED만_받고_관리자_역할이어도_ADMIN을_받지_못한다() {
		assertThat(authoritiesOf(MemberStatus.REJECTED, MemberRole.MEMBER)).containsExactly("ROLE_REJECTED");
		assertThat(authoritiesOf(MemberStatus.REJECTED, MemberRole.ADMIN)).containsExactly("ROLE_REJECTED");
	}

	@Test
	void 토큰에_status나_role_클레임이_있어도_DB_값을_따른다() {
		when(memberRepository.findById(42L)).thenReturn(Optional.of(member(42L, MemberStatus.PENDING, MemberRole.MEMBER)));
		Jwt forged = Jwt.withTokenValue("token")
				.header("alg", "HS256")
				.subject("42")
				.claim("status", "ACTIVE")
				.claim("role", "ADMIN")
				.claim("roles", List.of("ROLE_ADMIN"))
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();

		MemberAuthenticationToken authentication = converter.convert(forged);

		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_PENDING");
	}

	@Test
	void 인증_결과에_회원_id와_DB_상태가_담긴다() {
		when(memberRepository.findById(42L)).thenReturn(Optional.of(member(42L, MemberStatus.ACTIVE, MemberRole.ADMIN)));
		Jwt jwt = jwt("42");

		MemberAuthenticationToken authentication = converter.convert(jwt);

		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getPrincipal())
				.isEqualTo(new AuthenticatedMember(42L, MemberStatus.ACTIVE, MemberRole.ADMIN));
		assertThat(authentication.getName()).isEqualTo("42");
		assertThat(authentication.getCredentials()).isSameAs(jwt);
	}

	@Test
	void DB에_없는_회원이면_토큰_오류다() {
		when(memberRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> converter.convert(jwt("999"))).isInstanceOf(InvalidBearerTokenException.class);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "abc", "42abc", " 42", "42 ", "1.5", "1e3", "0x2A", "99999999999999999999"})
	void sub가_숫자가_아니거나_범위를_넘으면_토큰_오류다(String subject) {
		assertThatThrownBy(() -> converter.convert(jwt(subject))).isInstanceOf(InvalidBearerTokenException.class);
	}

}
