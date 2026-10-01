package com.jandilog.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 잔디 조회 설정 바인딩. AppConfig는 건드리지 않고 따로 등록한다
@Configuration
@EnableConfigurationProperties(GrassProperties.class)
public class GrassConfig {

}
