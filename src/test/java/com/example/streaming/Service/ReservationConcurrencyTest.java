package com.example.streaming.Service;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.RedisTestSupport;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 로컬 Redis + H2로 선점 경쟁과 예약 확정 흐름을 확인한다. Redis가 없으면 건너뛴다.
 * 스레드마다 별도 트랜잭션이 필요하므로 테스트 클래스에 @Transactional을 붙이지 않는다.
 */
@SpringBootTest
class ReservationConcurrencyTest {

    static final int THREAD_COUNT = 1000;

    @Autowired
    ReservationService reservationService;
    @Autowired
    RedisSeatHoldService redisSeatHoldService;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    SeatRepository seatRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ReservationRepository reservationRepository;

    EventEntity event;
    SeatEntity seat;
    List<Long> userIds;

    @BeforeEach
    void setUp() {
        assumeTrue(RedisTestSupport.isAvailable(), "로컬 Redis(localhost:6379)가 없어 건너뜁니다.");

        event = eventRepository.save(new EventEntity("콘서트", LocalDateTime.now().plusDays(7)));
        seat = seatRepository.save(new SeatEntity(event, 1));

        List<UserEntity> users = new ArrayList<>();
        for (int i = 1; i <= THREAD_COUNT; i++) {
            users.add(new UserEntity("user" + i + "@test.com", "password"));
        }
        // id는 자동 증가라 테스트마다 달라지므로 저장된 id를 쓴다
        userIds = userRepository.saveAll(users).stream().map(UserEntity::getId).toList();

        // H2는 매번 초기화돼 좌석 id가 재사용되므로, 이전 실행에서 남은 선점 키를 지운다
        redisTemplate.delete(redisSeatHoldService.createKey(seat.getId()));
    }

    @AfterEach
    void tearDown() {
        if (seat != null) {
            redisTemplate.delete(redisSeatHoldService.createKey(seat.getId()));
        }
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("같은 좌석을 동시에 1000명이 선점해도 1명만 성공하고, DB에는 아무것도 쓰지 않는다")
    void hold_concurrently() throws InterruptedException {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger unavailable = new AtomicInteger();
        AtomicInteger otherError = new AtomicInteger();
        AtomicLong winner = new AtomicLong();

        runConcurrently(userId -> {
            try {
                reservationService.holdSeat(event.getId(), seat.getId(), userId);
                success.incrementAndGet();
                winner.set(userId);
            } catch (SeatUnavailableException e) {
                unavailable.incrementAndGet();
            } catch (Exception e) {
                otherError.incrementAndGet();
            }
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(unavailable.get()).isEqualTo(THREAD_COUNT - 1);
        assertThat(otherError.get()).isZero();
        assertThat(redisSeatHoldService.getHolder(seat.getId())).isEqualTo(String.valueOf(winner.get()));
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    @DisplayName("선점한 사람만 예약을 확정할 수 있고, 확정 후에는 선점이 해제되며 다시 선점할 수 없다")
    void reserve_afterHold() {
        Long holder = userIds.get(0);
        Long other = userIds.get(1);
        reservationService.holdSeat(event.getId(), seat.getId(), holder);

        // 선점하지 않은 사용자는 확정 불가
        assertThatThrownBy(() -> reservationService.reserve(event.getId(), seat.getId(), other))
                .isInstanceOf(SeatUnavailableException.class);

        // 선점한 사용자는 확정 성공
        assertThat(reservationService.reserve(event.getId(), seat.getId(), holder).status())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isTrue();

        // 커밋 후 Redis 선점이 지워졌는지
        assertThat(redisSeatHoldService.getHolder(seat.getId())).isNull();

        // 이미 최종 예약된 좌석은 Redis 키가 없어도 선점 불가
        assertThatThrownBy(() -> reservationService.holdSeat(event.getId(), seat.getId(), other))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("이미 예약된 좌석입니다.");
    }

    @Test
    @DisplayName("선점→확정을 1000명이 동시에 시도해도 최종 예약은 1건이다")
    void holdAndReserve_concurrently() throws InterruptedException {
        AtomicInteger reserved = new AtomicInteger();

        runConcurrently(userId -> {
            try {
                reservationService.holdSeat(event.getId(), seat.getId(), userId);
                reservationService.reserve(event.getId(), seat.getId(), userId);
                reserved.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        assertThat(reserved.get()).isEqualTo(1);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    private void runConcurrently(java.util.function.LongConsumer task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        for (long userId : userIds) {
            executor.submit(() -> {
                try {
                    start.await();
                    task.accept(userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();
    }
}
