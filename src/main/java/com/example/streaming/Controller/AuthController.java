package com.example.streaming.Controller;

import com.example.streaming.Dto.LoginRequest;
import com.example.streaming.Dto.LoginResponse;
import com.example.streaming.Dto.SignupRequest;
import com.example.streaming.Dto.SignupResponse;
import com.example.streaming.Service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(
            @Valid @RequestBody SignupRequest request
            ){
            SignupResponse response = authService.signup(request);
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest loginRequest
    ){
        LoginResponse loginResponse = authService.login(loginRequest);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(loginResponse);
    }
}
