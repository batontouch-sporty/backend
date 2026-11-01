package com.example.sporty.user.commons.exception.users;

public class WithdrawalInProgressException extends RuntimeException {

    public WithdrawalInProgressException() {
        super("이미 탈퇴를 처리하고 있습니다.");
    }
}
