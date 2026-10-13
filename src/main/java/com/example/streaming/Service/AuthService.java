package com.example.streaming.Service;

import com.example.streaming.Dto.LoginRequest;
import com.example.streaming.Dto.LoginResponse;
import com.example.streaming.Dto.SignupRequest;
import com.example.streaming.Dto.SignupResponse;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request){

        if(userRepository.existsByEmail(request.email())){
            throw new IllegalArgumentException(
                    "이미 사용 중인 이메일입니다."
            );
        }
        String encodedPassword = passwordEncoder.encode(request.password());
        UserEntity user = new UserEntity(
                request.email(),
                encodedPassword
        );
        UserEntity saved = userRepository.save(user);
        return new SignupResponse(
                saved.getId(),
                saved.getEmail()
        );
    }
    public LoginResponse login(
            LoginRequest request
    ) {

        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken
                        .unauthenticated(
                                request.email(),
                                request.password()
                        )
        );

        UserEntity user =
                userRepository.findByEmail(
                                request.email()
                        )
                        .orElseThrow();

        String accessToken =
                tokenService.createAccessToken(user);

        return new LoginResponse(
                accessToken
        );
    }
}
