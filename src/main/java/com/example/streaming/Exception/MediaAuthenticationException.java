package com.example.streaming.Exception;

public class MediaAuthenticationException extends RuntimeException {
    public MediaAuthenticationException() {
        super("Media authentication failed");
    }
}
