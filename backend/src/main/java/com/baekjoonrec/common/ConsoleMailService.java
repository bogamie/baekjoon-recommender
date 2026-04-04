package com.baekjoonrec.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mail.dev-mode", havingValue = "true", matchIfMissing = true)
public class ConsoleMailService implements MailService {

    @Override
    public void sendVerificationCode(String email, String code) {
        log.info("========================================");
        log.info("Verification code for {}: {}", email, code);
        log.info("========================================");
    }
}
