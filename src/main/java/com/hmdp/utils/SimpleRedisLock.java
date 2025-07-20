package com.hmdp.utils;

import com.sun.javafx.beans.IDProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    private static final String KEY_PREFIX="lock:";
    //去掉uuid自带下划线-，添加自己的下划线-
    private static final String ID_PREFIX= UUID.randomUUID().toString().replace("-","") + "-";

    public SimpleRedisLock( String name,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    //Lua脚本初始化
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //类加载时自动初始化
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));//加载Lua脚本
        UNLOCK_SCRIPT.setResultType(Long.class);//设定Lua脚本的返回值为long
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //因为Boolean是包装类，而返回值boolean是基本类型，转化要自动拆箱，如果success是null的话会出问题
        //Boolean.TRUE.equals()避免了直接拆箱（如 return success; 会导致 NullPointerException）
        //success是true返回true，是false返回false，是null返回false
        return Boolean.TRUE.equals(success);
    }

    /**
     * Lua标本保证释放锁时的原子性操作（只有一条语句）
     */
    @Override
    public void unlock() {
        // 调用lua脚本，key转成集合
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());
    }

    /*@Override
    public void unlock() {
        //获取线程标识
        String threadId=ID_PREFIX+Thread.currentThread().getId();

        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //判断标识是否一致
        if (threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }*/
}
