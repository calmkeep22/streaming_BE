package com.example.streaming.Config;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import com.example.streaming.Service.RedisSeatHoldService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 부하 테스트용 데이터: 이벤트 1개(id=1), 좌석 100개(id=1~100), 사용자 1000명(id=1~1000)
 */
@Component
@Profile("loadtest")
public class LoadTestDataInitializer implements ApplicationRunner {

    private static final int SEAT_COUNT = 100;
    private static final int USER_COUNT = 1000;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisSeatHoldService redisSeatHoldService;
    private final PasswordEncoder passwordEncoder;

    public LoadTestDataInitializer(EventRepository eventRepository, SeatRepository seatRepository, UserRepository userRepository,
                                   StringRedisTemplate redisTemplate, RedisSeatHoldService redisSeatHoldService,
                                   PasswordEncoder passwordEncoder) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.redisSeatHoldService = redisSeatHoldService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        // DB는 매번 새로 만들어져 좌석 id가 1부터 재사용되므로, 이전 회차의 Redis 선점 키(TTL 5분)를 지운다
        List<String> holdKeys = new ArrayList<>();
        for (long seatId = 1; seatId <= SEAT_COUNT; seatId++) {
            holdKeys.add(redisSeatHoldService.createKey(seatId));
        }
        redisTemplate.delete(holdKeys);

        EventEntity event = eventRepository.save(
                new EventEntity("부하 테스트 콘서트", LocalDateTime.now().plusDays(7)));

        List<SeatEntity> seats = new ArrayList<>();
        for (int i = 1; i <= SEAT_COUNT; i++) {
            seats.add(new SeatEntity(event, i));
        }
        seatRepository.saveAll(seats);

        // 부하 스크립트는 로그인 대신 JWT를 직접 만들어 쓰므로(sub = 사용자 id) 비밀번호는 쓰이지 않는다
        String password = passwordEncoder.encode("loadtest-password");
        List<UserEntity> users = new ArrayList<>();
        for (int i = 1; i <= USER_COUNT; i++) {
            users.add(new UserEntity("user" + i + "@loadtest.local", password));
        }
        userRepository.saveAll(users);
    }
}
