package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

// .env의 ADMIN_GITHUB_IDS 줄이 어떻게 읽히는지 확인한다.
// application.yml의 optional:file:.env[.properties]와 같은 Properties 로더로 임시 파일을 읽고 실제 .env는 건드리지 않는다
class DotenvAdminIdsTest {

	private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

	@TempDir
	Path dir;

	// .env 내용을 application.yml 앞에 올려 jandilog.admin.github-ids 값을 읽는다
	private String adminValue(byte[] content) throws IOException {
		Path file = dir.resolve(".env");
		Files.write(file, content);
		MockEnvironment env = new MockEnvironment();
		for (PropertySource<?> source : new PropertiesPropertySourceLoader().load("dotenv", new FileSystemResource(file))) {
			env.getPropertySources().addLast(source);
		}
		for (PropertySource<?> source : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))) {
			env.getPropertySources().addLast(source);
		}
		return env.getProperty("jandilog.admin.github-ids");
	}

	private Set<Long> ids(String dotenv) throws IOException {
		return new AdminProperties(adminValue(dotenv.getBytes(StandardCharsets.UTF_8))).githubIdSet();
	}

	// ----- 정상으로 읽히는 형식 -----

	@Test
	void 한_줄_형식과_CRLF_마지막_줄_개행_없음은_그대로_읽힌다() throws IOException {
		assertThat(ids("ADMIN_GITHUB_IDS=12345678,87654321\n")).containsExactly(12345678L, 87654321L);
		assertThat(ids("DB_HOST=x\r\nADMIN_GITHUB_IDS=12345678,87654321\r\nJWT_EXPIRES_MINUTES=720\r\n"))
				.containsExactly(12345678L, 87654321L);
		assertThat(ids("DB_HOST=x\nADMIN_GITHUB_IDS=12345678")).containsExactly(12345678L);
	}

	@Test
	void 등호_앞뒤_공백과_값_뒤_공백은_잘라서_받는다() throws IOException {
		assertThat(ids("ADMIN_GITHUB_IDS = 12345678 , 87654321   \n")).containsExactly(12345678L, 87654321L);
	}

	@Test
	void 한글_주석이_앞에_있어도_읽힌다() throws IOException {
		assertThat(ids("# 로컬 개발용 접속 정보\nDB_HOST=x\n\n# 첫 관리자\nADMIN_GITHUB_IDS=12345678\n"))
				.containsExactly(12345678L);
	}

	// ----- 값이 틀리면 기동이 실패한다 (조용히 틀어지지 않음) -----

	@ParameterizedTest
	@ValueSource(strings = {
			"ADMIN_GITHUB_IDS=\"12345678\"\n",
			"ADMIN_GITHUB_IDS='12345678'\n",
			"ADMIN_GITHUB_IDS=12345678 # 내 계정\n",
			"ADMIN_GITHUB_IDS=12345678,\n"})
	void 따옴표_줄_끝_주석_끝_쉼표가_붙으면_기동이_실패한다(String dotenv) {
		assertThatThrownBy(() -> ids(dotenv)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ADMIN_GITHUB_IDS");
	}

	// ----- 줄이 읽히지 않아 목록이 빈 채로 뜨는 경우 (오류 없음, 아무도 승격되지 않음) -----

	@ParameterizedTest
	@ValueSource(strings = {
			"export ADMIN_GITHUB_IDS=12345678\n",
			"admin_github_ids=12345678\n",
			"ADMIN_GITHUB_IDS=12345678\nADMIN_GITHUB_IDS=\n"})
	void export_접두_소문자_키_뒤에서_다시_빈_값으로_적은_줄은_목록이_빈다(String dotenv) throws IOException {
		assertThat(ids(dotenv)).isEmpty();
	}

	@Test
	void UTF8_BOM이_붙은_파일은_첫_줄_키를_못_읽는다() throws IOException {
		byte[] bomFirstLine = concat(BOM, "ADMIN_GITHUB_IDS=12345678\n".getBytes(StandardCharsets.UTF_8));
		byte[] bomComment = concat(BOM, "# 주석\nADMIN_GITHUB_IDS=12345678\n".getBytes(StandardCharsets.UTF_8));

		assertThat(new AdminProperties(adminValue(bomFirstLine)).githubIdSet()).isEmpty();
		// 첫 줄이 주석이면 영향이 없다
		assertThat(new AdminProperties(adminValue(bomComment)).githubIdSet()).containsExactly(12345678L);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] result = new byte[a.length + b.length];
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

}
