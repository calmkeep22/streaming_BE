package com.example.streaming.Exception;

public class SeatUnavailableException extends RuntimeException{
    public SeatUnavailableException(String message){
        super(message);
    }
}
