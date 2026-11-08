package com.stm.middleware.banking.api;

public interface NotificationPort {
    void send(Channel channel, String to, String subject, String body);

    enum Channel { EMAIL, SMS }
}
