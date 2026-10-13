package com.example.streaming;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 로컬 Redis(localhost:6379)가 떠 있는지 확인한다.
 * Redis가 필요한 테스트는 Assumptions.assumeTrue(RedisTestSupport.isAvailable())로 Redis가 없으면 건너뛴다.
 */
public final class RedisTestSupport {

    public static final String HOST = "localhost";
    public static final int PORT = 6379;
    public static final int TEST_DATABASE = 15;

    private RedisTestSupport() {
    }

    public static boolean isAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
