package com.example.streaming.Config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "media.enabled", havingValue = "true")
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {
}
