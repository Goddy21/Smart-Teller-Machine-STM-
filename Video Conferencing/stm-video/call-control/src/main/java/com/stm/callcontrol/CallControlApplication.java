package com.stm.callcontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CallControlApplication {
  public static void main(String[] args) { SpringApplication.run(CallControlApplication.class, args); }
}
