package com.example.streaming.Config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {
    @Bean
    public OpenAPI openAPI() {
        String jwtSchemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("Seat Reservation API").description("좌석 예약 서비스 API").version("1.0"))
                .addSecurityItem(new SecurityRequirement().addList(jwtSchemeName))
                .components(new Components().addSecuritySchemes(jwtSchemeName,
                        new SecurityScheme().name(jwtSchemeName).type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")));
    }
}
