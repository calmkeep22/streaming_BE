package com.example.streaming;

import com.example.streaming.Entity.*;
import com.example.streaming.Repository.*;
import com.example.streaming.Service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:media-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaMtxPlaybackIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        PlaybackAuthIntegrationTest.mediaProperties(registry);
    }
    @LocalServerPort int port;
    @Autowired ReservationService reservations;
    @Autowired PlaybackService playback;
    @Autowired AdmissionService admissions;
    @Autowired MediaTicketCodec tickets;
    @Autowired EventRepository events;
    @Autowired SeatRepository seats;
    @Autowired UserRepository users;
    @MockitoBean RedisSeatHoldService holds;
    GenericContainer<?> media;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String base;
    UserEntity user;
    EventEntity event;
    SeatEntity seat;
    com.example.streaming.Dto.ReservationResponse reservation;
    String anotherPath;

    @BeforeAll void startMedia() throws Exception {
        given(holds.isHeldBy(anyLong(), anyLong())).willReturn(true);
        user = users.save(new UserEntity("media-e2e@test.com", "password"));
        event = events.save(new EventEntity("Media test", LocalDateTime.now().plusDays(1)));
        seat = seats.save(new SeatEntity(event, 1, SeatGrade.FREE, 0L));
        reservation = reservations.reserveFree(event.getId(), seat.getId(), user.getId());
        anotherPath = "events/" + (event.getId() + 1);
        Testcontainers.exposeHostPorts(port);
        String config = Files.readString(Path.of("deploy/media/mediamtx.yml"))
                // MediaMTX permits alwaysAvailable on concrete paths only; auth remains the production HTTP flow.
                .replace("\"~^events/[1-9][0-9]*$\":", "\"events/" + event.getId() + "\":\n  \"" + anotherPath + "\":")
                + "\npathDefaults:\n  alwaysAvailable: true\n  alwaysAvailableTracks:\n    - codec: H264\n";
        media = new GenericContainer<>("bluenviron/mediamtx:1.21.4")
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8)), "/mediamtx.yml")
                .withEnv("MTX_AUTHHTTPADDRESS", "http://host.testcontainers.internal:" + port + "/internal/media/auth")
                .withEnv("MTX_READTIMEOUT", "2s").withEnv("MTX_WEBRTCHANDSHAKETIMEOUT", "30s")
                .withExposedPorts(8889, 9997)
                // Distroless image has no shell for internal port probes.
                .waitingFor(Wait.forHttp("/v3/paths/list").forPort(9997)
                        .withBasicCredentials("monitor", "monitor-test").forStatusCode(200));
        media.start();
        base = "http://" + media.getHost() + ":" + media.getMappedPort(8889);
    }

    @AfterAll void stopMedia() { if (media != null) media.stop(); }

    @Test void actualWhepChecksTokensRevocationExpiryAndFailsClosed() throws Exception {
        var ticket = playback.issue(event.getId(), seat.getId(), user.getId(), Instant.now().plusSeconds(3600));
        String path = "/" + ticket.path() + "/whep";
        assertThat(whep(path, null).statusCode()).isIn(401, 403);
        String legacy = "Basic " + Base64.getEncoder().encodeToString("viewer:old-shared-password".getBytes(StandardCharsets.UTF_8));
        assertThat(whep(path, legacy).statusCode()).isIn(401, 403);
        assertThat(whep("/" + anotherPath + "/whep", "Bearer " + ticket.token()).statusCode()).isIn(401, 403);
        HttpResponse<String> connected = whep(path, "Bearer " + ticket.token());
        assertThat(connected.statusCode()).as("valid WHEP ticket").isEqualTo(201);
        assertThat(connected.body()).contains("m=video", "a=ice-ufrag:");
        closeSession(connected);
        reservations.cancel(reservation.reservationId(), user.getId());
        assertThat(whep(path, "Bearer " + ticket.token()).statusCode()).isIn(401, 403);

        reservations.reserveFree(event.getId(), seat.getId(), user.getId());
        var admission = admissions.getAdmission(event.getId(), seat.getId(), user.getId());
        await().atMost(Duration.ofSeconds(5)).until(() -> sessionCount() == 0);
        String lease = tickets.issue(admission, user.getId(), Instant.now(), Instant.now().plusSeconds(3));
        assertThat(whep(path, "Bearer " + lease).statusCode()).isEqualTo(201);
        assertThat(sessionCount()).isEqualTo(1);
        // The handshake timeout is 30 seconds, so disappearance within 8 proves the auth lease ended it.
        await().atMost(Duration.ofSeconds(8)).until(() -> sessionCount() == 0);

        String control = "http://" + media.getHost() + ":" + media.getMappedPort(9997);
        var patch = client.send(HttpRequest.newBuilder(URI.create(control + "/v3/config/global/patch"))
                .header("Authorization", monitor()).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"authHTTPAddress\":\"http://127.0.0.1:1/auth\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(patch.statusCode()).isEqualTo(200);
        var denied = whep(path, "Bearer " + playback.issue(event.getId(), seat.getId(), user.getId(), Instant.now().plusSeconds(3600)).token());
        assertThat(denied.statusCode()).as("unreachable auth service must deny playback").isIn(401, 403, 500, 503);
    }

    int sessionCount() throws Exception {
        String url = "http://" + media.getHost() + ":" + media.getMappedPort(9997) + "/v3/webrtcsessions/list";
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", monitor()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return ((java.util.List<?>) com.jayway.jsonpath.JsonPath.read(response.body(), "$.items")).size();
    }
    String monitor() {
        return "Basic " + Base64.getEncoder().encodeToString("monitor:monitor-test".getBytes(StandardCharsets.UTF_8));
    }
    void closeSession(HttpResponse<?> response) throws Exception {
        URI location = URI.create(base).resolve(response.headers().firstValue("Location").orElseThrow());
        var result = client.send(HttpRequest.newBuilder(location).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(result.statusCode()).isIn(200, 204);
    }
    HttpResponse<String> whep(String path, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/sdp").POST(HttpRequest.BodyPublishers.ofString(OFFER.replace("\n", "\r\n")));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static final String OFFER = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=group:BUNDLE 0
            a=msid-semantic: WMS
            m=video 9 UDP/TLS/RTP/SAVPF 96
            c=IN IP4 0.0.0.0
            a=mid:0
            a=recvonly
            a=rtcp-mux
            a=ice-ufrag:mediatest
            a=ice-pwd:mediaauthenticationtestpassword
            a=fingerprint:sha-256 00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F
            a=setup:actpass
            a=rtpmap:96 H264/90000
            a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
            """;
}
