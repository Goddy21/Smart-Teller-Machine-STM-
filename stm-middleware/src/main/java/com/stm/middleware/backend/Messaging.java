package com.stm.middleware.backend;

/** SMS gateway. */
public interface Messaging {
    /** Sends the text; returns the provider's message id. */
    String sendSms(String mobile, String text);
}
