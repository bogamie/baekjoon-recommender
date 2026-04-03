package com.baekjoonrec.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConsoleMailService implements MailService {

    @Override
    public void sendVerificationCode(String email, String code) {
        log.info("========================================");
        log.info("Verification code for {}: {}", email, code);
        log.info("========================================");
    }
}
