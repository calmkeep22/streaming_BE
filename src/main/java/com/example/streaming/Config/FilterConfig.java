package com.example.streaming.Config;

import com.example.streaming.Filter.RequestTimingFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RequestTimingFilter> requestTimingFilter(
            MeterRegistry meterRegistry
    ) {

        FilterRegistrationBean<RequestTimingFilter> registration =
                new FilterRegistrationBean<>();

        registration.setFilter(
                new RequestTimingFilter(meterRegistry)
        );

        registration.setOrder(
                Ordered.HIGHEST_PRECEDENCE
        );

        registration.addUrlPatterns("/*");

        return registration;
    }
}