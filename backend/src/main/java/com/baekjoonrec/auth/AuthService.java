package com.baekjoonrec.auth;

import com.baekjoonrec.auth.dto.*;
import com.baekjoonrec.common.ApiException;
import com.baekjoonrec.common.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;

    public AvailabilityResponse checkEmail(CheckEmailRequest request) {
        boolean exists = userRepository.existsByEmail(request.getEmail());
        return new AvailabilityResponse(!exists);
    }

    public AvailabilityResponse checkUsername(CheckUsernameRequest request) {
        boolean exists = userRepository.existsByUsername(request.getUsername());
        return new AvailabilityResponse(!exists);
    }

    @Transactional
    public void sendCode(SendCodeRequest request) {
        String code = String.format("%06d", new Random().nextInt(999999));
        EmailVerification verification = EmailVerification.builder()
                .email(request.getEmail())
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .verified(false)
                .build();
        emailVerificationRepository.save(verification);
        mailService.sendVerificationCode(request.getEmail(), code);
    }

    @Transactional
    public void verifyCode(VerifyCodeRequest request) {
        EmailVerification verification = emailVerificationRepository
                .findTopByEmailOrderByCreatedAtDesc(request.getEmail())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "CODE_NOT_FOUND", "No verification code found for this email"));

        if (verification.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED", "Verification code has expired");
        }
        if (!verification.getCode().equals(request.getCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "Invalid verification code");
        }

        verification.setVerified(true);
        emailVerificationRepository.save(verification);
    }

    @Transactional
    public TokenResponse signup(SignupRequest request) {
        // Check email verified
        EmailVerification verification = emailVerificationRepository
                .findTopByEmailAndVerifiedTrueOrderByCreatedAtDesc(request.getEmail())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_NOT_VERIFIED", "Email must be verified before signup"));

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email already registered");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "Username already taken");
        }

        User user = User.builder()
                .email(request.getEmail())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .emailVerified(true)
                .solvedacHandle(request.getSolvedacHandle())
                .build();
        user = userRepository.save(user);

        return generateTokens(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return generateTokens(user);
    }

    @Transactional
    public AccessTokenResponse refresh(RefreshRequest request) {
        if (!jwtService.isTokenValid(request.getRefreshToken())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid or expired refresh token");
        }

        RefreshToken stored = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Refresh token not found"));

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Refresh token has expired");
        }

        Long userId = jwtService.extractUserId(request.getRefreshToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        return new AccessTokenResponse(accessToken);
    }

    private TokenResponse generateTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        RefreshToken tokenEntity = RefreshToken.builder()
                .userId(user.getId())
                .token(refreshToken)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(tokenEntity);

        return new TokenResponse(accessToken, refreshToken);
    }
}
