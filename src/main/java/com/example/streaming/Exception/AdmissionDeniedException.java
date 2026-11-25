package com.example.streaming.Exception;

public class AdmissionDeniedException extends RuntimeException {
    public AdmissionDeniedException() {
        super("유효한 입장 권한이 없습니다.");
    }
}
