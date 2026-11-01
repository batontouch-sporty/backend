package com.example.sporty.match.withdrawal;

/** Another operation still owns the member lock after the configured wait. */
public class MemberLockTimeoutException extends IllegalStateException {
    public MemberLockTimeoutException() {
        super("회원 작업 처리 중");
    }
}
