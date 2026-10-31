package com.example.sporty.notification.commons.exception.notifications;

public class NotificationForbiddenException extends RuntimeException {

    public NotificationForbiddenException() {
        super("다른 회원의 알림입니다.");
    }
}
