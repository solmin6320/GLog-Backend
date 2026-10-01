package com.jandilog.testsupport.auth;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// @PreAuthorize가 없는 REST 엔드포인트. URL 체인의 기본 거부(hasRole MEMBER)만으로 막히는지 본다
@TestComponent
@RestController
public class AuthProbeRestController {

	@GetMapping("/probe-rest")
	public String open() {
		return "open";
	}

}
