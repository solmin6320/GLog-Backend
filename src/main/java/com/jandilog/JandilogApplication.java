package com.jandilog;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JandilogApplication {

	public static void main(String[] args) {
		// JVM 기본 시간대를 Asia/Seoul로 고정 (DB명세서 1-0)
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		SpringApplication.run(JandilogApplication.class, args);
	}

}
