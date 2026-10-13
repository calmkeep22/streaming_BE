package com.example.streaming.Config;

import com.example.streaming.Aspect.HandlerTimingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final HandlerTimingInterceptor handlerTimingInterceptor;

    public WebConfig(
            HandlerTimingInterceptor handlerTimingInterceptor
    ) {
        this.handlerTimingInterceptor =
                handlerTimingInterceptor;
    }

    @Override
    public void addInterceptors(
            InterceptorRegistry registry
    ) {

        registry.addInterceptor(
                        handlerTimingInterceptor
                )
                .addPathPatterns("/events/seats/**");
    }
}