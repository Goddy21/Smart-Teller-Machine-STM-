package com.stm.callcontrol;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
public class RedisConfig {
  @Bean
  RedisMessageListenerContainer listeners(RedisConnectionFactory cf, StreamHub hub) {
    var c = new RedisMessageListenerContainer();
    c.setConnectionFactory(cf);
    c.addMessageListener((msg, pattern) -> hub.onMessage(new String(msg.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(StreamHub.CHANNEL));
    return c;
  }
}
