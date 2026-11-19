package com.example.streaming.Config;

import com.example.streaming.Service.CustomUserDetailService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;
    @Value("${management.server.port:-1}")
    private int managementPort;
    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    )throws Exception{
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth-> auth
                        .requestMatchers(
                                "/auth/signup",
                                "/auth/login",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/payment-test.html"
                        ).permitAll()
                        .requestMatchers(this::isManagementMonitoringRequest).permitAll()
                        .requestMatchers(HttpMethod.POST, "/events").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .build();
    }

    /**
     * Prometheus 수집과 헬스 체크는 토큰 없이 받는다. 단, 관리 포트(management.server.port)로 들어온 요청만.
     * 앱 포트에서도 열면 health 상세(DB·Redis 상태)와 전체 지표가 외부에 그대로 보이므로, 앱 포트에서는 인증이 필요하다.
     * 관리 포트를 따로 두지 않은 기본 프로필에서는 managementPort가 -1이라 항상 인증이 필요하다.
     */
    private boolean isManagementMonitoringRequest(HttpServletRequest request) {
        if (managementPort <= 0 || request.getLocalPort() != managementPort) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.equals("/actuator/prometheus")
                || uri.equals("/actuator/health")
                || uri.startsWith("/actuator/health/");
    }

    // 토큰의 role 클레임(USER, ADMIN)을 ROLE_ 권한으로 바꾼다 (TokenService가 넣는 값)
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("role");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            CustomUserDetailService userDetailService,
            PasswordEncoder passwordEncoder
    ){
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(
                userDetailService
        );
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
    @Bean
    public SecretKey jwtSecretKey() {
        byte[] decodedKey =
                Base64.getDecoder().decode(jwtSecret);

        return new SecretKeySpec(
                decodedKey,
                "HmacSHA256"
        );
    }

    @Bean
    public JwtEncoder jwtEncoder(
            SecretKey secretKey
    ) {
        return NimbusJwtEncoder
                .withSecretKey(secretKey)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey secretKey
    ) {
        return NimbusJwtDecoder
                .withSecretKey(secretKey)
                .build();
    }

}
