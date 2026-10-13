package com.example.streaming.Config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SwaggerConfigTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없이 OpenAPI 명세와 JWT 인증 설정 및 취소 응답을 조회한다")
    void apiDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/events/reservations/{reservationId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/auth/signup'].post.security").isEmpty());
    }

    @Test
    @DisplayName("토큰 없이 Swagger UI에 접근한다")
    void swaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Swagger를 공개해도 좌석 API는 여전히 인증이 필요하다")
    void seatApiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/events/seats/1"))
                .andExpect(status().isUnauthorized());
    }
}
