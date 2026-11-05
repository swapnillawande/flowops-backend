package com.flowops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

/** One injectable UTC clock keeps deadlines testable without sleeping or changing database timestamps. */
@Configuration
@EnableScheduling
public class TimeConfiguration {
    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }
}
