package com.example.streaming;

import com.example.streaming.Entity.*;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Repository.*;
import com.example.streaming.Service.RedisSeatHoldService;
import com.example.streaming.Service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class ReservationConcurrencyIntegrationTest {

    @Container
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0.44")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {

        // MySQL
        registry.add(
                "spring.datasource.url",
                mysql::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                mysql::getUsername
        );

        registry.add(
                "spring.datasource.password",
                mysql::getPassword
        );

        // test/resources의 H2 드라이버 설정을 덮어쓴다
        registry.add(
                "spring.datasource.driver-class-name",
                mysql::getDriverClassName
        );

        // Redis
        registry.add(
                "spring.data.redis.host",
                redis::getHost
        );

        registry.add(
                "spring.data.redis.port",
                () -> redis.getMappedPort(6379)
        );

        // 테스트가 끝나면 DB도 사라지므로 테스트용으로 사용 가능
        registry.add(
                "spring.jpa.hibernate.ddl-auto",
                () -> "create-drop"
        );
    }

    @Autowired
    ReservationService reservationService;

    @Autowired
    ReservationRepository reservationRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RedisSeatHoldService redisSeatHoldService;


    @BeforeEach
    void cleanUp() {

        // Redis 초기화
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });

        // FK 관계 때문에 자식부터 삭제
        reservationRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
    }


    @Test
    void 예약을_취소한_후_100명이_동시에_Hold하면_한명만_성공한다()
            throws Exception {

        /*
         * given
         */

        UserEntity firstUser = createUser("first@test.com");

        EventEntity event = createEvent();

        SeatEntity seat = createSeat(event, 5000);


        // 최초 예약
        reservationService.holdSeat(
                event.getId(),
                seat.getId(),
                firstUser.getId()
        );

        var reservation =
                reservationService.reserve(
                        event.getId(),
                        seat.getId(),
                        firstUser.getId()
                );


        // 예약 취소
        reservationService.cancel(
                firstUser.getId(),
                reservation.reservationId()
        );


        // 취소 상태 확인
        ReservationEntity cancelled =
                reservationRepository
                        .findById(reservation.reservationId())
                        .orElseThrow();

        assertThat(cancelled.getStatus())
                .isEqualTo(ReservationStatus.CANCELED);

        assertThat(cancelled.getActiveSeatId())
                .isNull();


        /*
         * 새로운 사용자 100명 생성
         */

        int userCount = 100;

        List<UserEntity> users = new ArrayList<>();

        for (int i = 0; i < userCount; i++) {
            users.add(
                    createUser("user" + i + "@test.com")
            );
        }


        /*
         * when
         */

        ExecutorService executor =
                Executors.newFixedThreadPool(userCount);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        CountDownLatch doneLatch =
                new CountDownLatch(userCount);


        AtomicInteger successCount =
                new AtomicInteger();

        AtomicInteger failCount =
                new AtomicInteger();

        AtomicReference<Long> successUserId =
                new AtomicReference<>();


        for (UserEntity user : users) {

            executor.submit(() -> {

                try {

                    // 모든 스레드가 여기서 대기
                    startLatch.await();

                    reservationService.holdSeat(
                            event.getId(),
                            seat.getId(),
                            user.getId()
                    );

                    successCount.incrementAndGet();

                    successUserId.set(user.getId());

                } catch (Exception e) {

                    failCount.incrementAndGet();

                } finally {

                    doneLatch.countDown();
                }
            });
        }


        // 100개 스레드 동시 출발
        startLatch.countDown();

        // 전부 종료할 때까지 대기
        doneLatch.await();

        executor.shutdown();


        /*
         * then
         */

        System.out.println(
                "Hold 성공 = " + successCount.get()
        );

        System.out.println(
                "Hold 실패 = " + failCount.get()
        );


        assertThat(successCount.get())
                .isEqualTo(1);

        assertThat(failCount.get())
                .isEqualTo(99);


        /*
         * Hold 성공한 사용자가 최종 예약
         */

        Long winnerId =
                successUserId.get();


        var newReservation =
                reservationService.reserve(
                        event.getId(),
                        seat.getId(),
                        winnerId
                );


        assertThat(newReservation.status())
                .isEqualTo(ReservationStatus.RESERVED);


        /*
         * DB 최종 상태 확인
         */

        List<ReservationEntity> reservations =
                reservationRepository.findAll();


        long reservedCount =
                reservations.stream()
                        .filter(r ->
                                r.getSeat().getId()
                                        .equals(seat.getId())
                        )
                        .filter(r ->
                                r.getStatus()
                                        == ReservationStatus.RESERVED
                        )
                        .count();


        long cancelledCount =
                reservations.stream()
                        .filter(r ->
                                r.getSeat().getId()
                                        .equals(seat.getId())
                        )
                        .filter(r ->
                                r.getStatus()
                                        == ReservationStatus.CANCELED
                        )
                        .count();


        assertThat(reservedCount)
                .isEqualTo(1);

        assertThat(cancelledCount)
                .isEqualTo(1);
    }

    @Test
    void Hold_TTL이_만료되면_기존_사용자는_예약할_수_없고_다른_사용자가_선점할_수_있다()
            throws Exception {

        UserEntity firstUser = createUser("first-expire@test.com");
        UserEntity nextUser = createUser("next-expire@test.com");
        EventEntity event = createEvent();
        SeatEntity seat = createSeat(event, 5001);

        reservationService.holdSeat(
                event.getId(),
                seat.getId(),
                firstUser.getId()
        );

        String holdKey = redisSeatHoldService.createKey(seat.getId());
        assertThat(redisSeatHoldService.getHolder(seat.getId()))
                .isEqualTo(firstUser.getId().toString());

        // 운영 TTL(기본 5분)을 기다리지 않도록 이 테스트의 선점 키만 짧게 만료시킨다.
        assertThat(redisTemplate.expire(holdKey, 300, TimeUnit.MILLISECONDS))
                .isTrue();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (redisSeatHoldService.getHolder(seat.getId()) != null
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(redisSeatHoldService.getHolder(seat.getId())).isNull();

        assertThatThrownBy(() -> reservationService.reserve(
                event.getId(),
                seat.getId(),
                firstUser.getId()
        ))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("선점 중인 좌석이 아니거나 선점 시간이 만료되었습니다.");

        assertThat(reservationService.holdSeat(
                event.getId(),
                seat.getId(),
                nextUser.getId()
        ).status()).isEqualTo(SeatStatus.HELD);

        assertThat(redisSeatHoldService.getHolder(seat.getId()))
                .isEqualTo(nextUser.getId().toString());
    }


    /*
     * ============================
     * 테스트 fixture
     * ============================
     *
     * 아래 생성자는 네 Entity 구조에 맞춰 수정하면 된다.
     */

    private UserEntity createUser(String email) {

        UserEntity user =
                new UserEntity(
                        email,
                        "password"
                );

        return userRepository.save(user);
    }


    private EventEntity createEvent() {

        EventEntity event =
                new EventEntity(
                        "Test Event",
                        LocalDateTime.now().plusDays(1)
                );

        return eventRepository.save(event);
    }


    private SeatEntity createSeat(
            EventEntity event,
            Integer seatNumber
    ) {

        SeatEntity seat =
                new SeatEntity(
                        event,
                        seatNumber
                );

        return seatRepository.save(seat);
    }
}
