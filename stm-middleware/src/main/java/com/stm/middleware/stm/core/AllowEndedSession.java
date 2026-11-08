package com.stm.middleware.stm.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Clean-up calls the kiosk must still be able to make after D-4 ended the session with open items
 * (cancel a deposit, confirm or reverse a withdrawal, report a card dispensed or retained).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowEndedSession {
}
