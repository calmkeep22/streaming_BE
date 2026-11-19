package com.example.streaming.Config;

import com.example.streaming.Controller.EventController;
import com.example.streaming.Service.CustomUserDetailService;
import com.example.streaming.Service.EventService;
import com.example.streaming.Service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모니터링 엔드포인트는 관리 포트로 들어온 요청만 인증 없이 통과하는지 확인한다.
 * 이 슬라이스에는 Actuator 엔드포인트가 없으므로, 보안을 통과하면 404, 막히면 401이 된다.
 */
@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, ManagementEndpointSecurityTest.MetricsConfig.class})
@TestPropertySource(properties = "management.server.port=8081")
class ManagementEndpointSecurityTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReservationService reservationService;
    @MockitoBean
    EventService eventService;
    @MockitoBean
    CustomUserDetailService customUserDetailService;

    @Test
    @DisplayName("관리 포트로 들어온 prometheus, health 요청은 토큰 없이 통과한다")
    void managementPort_permitsMonitoring() throws Exception {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/health", "/actuator/health/liveness"}) {
            mockMvc.perform(get(path).with(port(8081)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("앱 포트로 들어온 모니터링 요청은 토큰이 없으면 401이다")
    void appPort_requiresToken() throws Exception {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/health"}) {
            mockMvc.perform(get(path).with(port(8080)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("관리 포트라도 prometheus, health 외의 엔드포인트는 토큰이 없으면 401이다")
    void managementPort_otherEndpointsRequireToken() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(port(8081)))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor port(int port) {
        return request -> {
            request.setLocalPort(port);
            return request;
        };
    }
}
