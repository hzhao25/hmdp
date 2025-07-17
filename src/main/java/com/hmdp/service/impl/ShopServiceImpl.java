package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.apache.tomcat.jni.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        /*//解决缓存穿透(自己实现代码)
        Shop shop = queryWithPassThrough(id);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }*/

        /*//解决缓存穿透，调用自己实现代码封装好的工具类
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,
                id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }*/

        /*//解决缓存穿透的基础上，互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }*/

        /*//不考虑缓存穿透问题，只用逻辑过期时间解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }*/

        //考虑缓存穿透问题，只用逻辑过期时间解决缓存击穿，调用自己实现代码封装好的工具类
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,
                id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }

        //7、返回
        return Result.ok(shop);
    }

    //不考虑缓存穿透问题，只用逻辑过期时间解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //1、从redis查询商铺缓存
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、判断是否不存在
        if(StrUtil.isBlank(shopJson)){
            //3、不存在，直接返回
            return null;
        }

        //4、命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5、判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return shop;
        }

        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock){
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //7、返回
        return shop;
    }

    //用来把shop数据和逻辑过期时间一起写入到redis当中
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1、查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3、写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    //用setnx实现互斥锁解决缓存击穿，用存储""值解决缓存穿透
    public Shop queryWithMutex(Long id) {
        //1、从redis查询商铺缓存
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在，直接返回
            //转为shop类型
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //不存在，判断命中是否为空值""，返回错误信息
        if(shopJson!=null){
            return null;
        }

        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            //4、实现缓存重建
            //4.1获取互斥锁
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //成功后，再次判断商铺是否存在缓存数据，因为可能在第一个线程释放锁后被第二个线程拿到了
            shopJson = stringRedisTemplate.opsForValue().get(key);

            //2、判断是否存在
            if(StrUtil.isNotBlank(shopJson)){
                //3、存在，直接返回
                //转为shop类型
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //不存在，判断命中是否为空值""，返回错误信息
            if(shopJson!=null){
                return null;
            }

            //4.4成功，根据id查询数据库
            shop = getById(id);

            //5、查询数据库不存在，返回错误
            if(shop==null){
                //将空值写入redis，设置过期时间，防止以后存在的话找不到
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //6、存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            unlock(lockKey);
        }

        //7、返回
        return shop;
    }

    //只解决缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //1、从redis查询商铺缓存
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在，直接返回
            //转为shop类型
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //不存在，判断命中是否为空值""，返回错误信息
        if(shopJson!=null){
            return null;
        }

        //4、不存在，根据id查询数据库
        Shop shop = getById(id);

        //5、查询数据库不存在，返回错误
        if(shop==null){
            //将空值写入redis，设置过期时间，防止以后存在的话找不到
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //6、存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7、返回
        return shop;
    }

    //加锁,setnx
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.MINUTES);
        //直接传递过去可能会拆箱，变为空值
        return BooleanUtil.isTrue(flag);
    }

    //释放锁，setnx
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空！");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
