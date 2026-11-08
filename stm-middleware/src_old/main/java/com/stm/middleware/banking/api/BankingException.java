package com.stm.middleware.banking.api;

public class BankingException extends RuntimeException {
    private final String code;

    public BankingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
