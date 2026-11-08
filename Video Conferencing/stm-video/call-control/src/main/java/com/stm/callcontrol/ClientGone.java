package com.stm.callcontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/** A browser that closed an event stream (page closed, call ended, Wi-Fi dropped) makes the next SSE write fail.
 *  That is normal. On Windows the message ("An established connection was aborted...") is not one Spring recognises,
 *  so without this every disconnect is logged as a full ERROR stack trace. */
@RestControllerAdvice
public class ClientGone {
  private static final Logger log = LoggerFactory.getLogger(ClientGone.class);

  @ExceptionHandler(IOException.class)
  public void clientDisconnected(IOException e) {
    log.debug("client disconnected: {}", e.getMessage());
  }
}
