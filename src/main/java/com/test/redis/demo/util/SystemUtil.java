package com.test.redis.demo.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SystemUtil {

    public String generatedUuid(){
        return UUID.randomUUID().toString();
    }
}
