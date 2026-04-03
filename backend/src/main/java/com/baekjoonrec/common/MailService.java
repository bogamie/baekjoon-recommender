package com.baekjoonrec.common;

public interface MailService {
    void sendVerificationCode(String email, String code);
}
