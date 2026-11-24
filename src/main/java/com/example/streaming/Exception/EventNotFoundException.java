package com.example.streaming.Exception;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException() {
        super("이벤트가 존재하지 않습니다.");
    }
}
