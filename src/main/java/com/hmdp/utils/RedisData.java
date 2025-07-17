package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用来实现逻辑过期时间方式redis缓存击穿
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
