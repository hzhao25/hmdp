package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;
    //创建固定大小为 500 的线程池
    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Test
    void testIdWork() throws InterruptedException {
        //等待300个线程完成任务
        CountDownLatch latch=new CountDownLatch(300);

        Runnable task=() -> {
            //每个线程生成100个id
            for(int i=0;i<100;i++){
                long id=redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            //每个线程等待,线程完成任务，计数器减 1
            latch.countDown();
        };
        long begin= System.currentTimeMillis();
        // 提交 300 个任务到线程池（模拟300个线程，）
        for(int i=0;i<300;i++){
            es.submit(task);//每个 task 由一个独立线程执行
        }
        //主线程等待所有线程结束
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }

    @Test
    void testSaveExpireTime() throws InterruptedException {
//        shopService.saveShop2Redis(1L,10L);
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
    }
}
