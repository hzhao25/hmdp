package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //开始时间戳
    private long beginTimestamp=this.beginTimestamp(2022,1,1,0,0,0);

    //序列号的位数
    private static final int COUNT_BITS=32;

    private StringRedisTemplate stringRedisTemplate;

    //RedisIdWorker和StringRedisTemplate都被Spring容器管理，spring容器会帮你自动注入
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    //生成全局唯一ID,key的全局唯一，返回Long，刚好long是64位
    public long nextId(String keyPrefix){
        //1、生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond-beginTimestamp;

        //2、生成序列号
        //redis的increment自增是有范围的共64位，我们用了低32位，如果单独一个key的增长值超过2^32，
        // 低32会回绕，导致订单id重复
        //2.1获取当前日期，精确到天，这样子做的好处可以避免避免单个 Redis key 的 INCR 值过大（超过 2^32），
        // 还能精准统计哪年哪月哪日的下单数
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长，每次调用increment，value+1=count
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        //3、拼接时间戳和序列号并返回
        //timestamp 左移 32 位，给 count 腾出空间（1111101000 00000000000000000000000000000000）
        //用 按位或（OR） 操作，将 count 填入低 32 位（1111101000 00000000000000000000000000000101）
        return timestamp << COUNT_BITS | count;
    }

    //生成特定时间的时间戳（一共多少秒）
    private static Long beginTimestamp(int year, int month, int day, int hour, int minute, int second){
        //创建一个时间对象
        LocalDateTime time=LocalDateTime.of(year,month,day,hour,minute,second);
        //将时间对象转化为时间戳（多少秒）
        long secondTime = time.toEpochSecond(ZoneOffset.UTC);
        return secondTime;
    }
}
