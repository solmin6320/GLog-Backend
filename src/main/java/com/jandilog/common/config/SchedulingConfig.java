package com.jandilog.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 작업을 켠다. 통합 테스트가 공유 DB를 건드리지 않게 jandilog.scheduler.enabled=false로 끌 수 있다
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jandilog.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

}
