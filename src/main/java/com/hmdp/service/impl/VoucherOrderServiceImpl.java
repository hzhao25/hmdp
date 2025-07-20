package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠券，别人的crud方法，不能直接用
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2、判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始!");
        }

        //3、判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //已经结束
            return Result.fail("秒杀已经结束");
        }

        //4、判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足!");
        }

        //我们选择在调用CreateVoucherOrder之前加锁，因为如果在CreateVoucherOrder里面加锁的话
        //会先释放锁，然后提交事务，但是因为释放了锁，导致其他线程又获取锁，上一个事务无法提交，就会导致修改不能同步
        //在调用CreateVoucherOrder之前加锁，可以让事务先提交，然后再释放锁
        Long userId = UserHolder.getUser().getId();

        //不在方法上加锁，因为会大大降低性能
        // 把锁粒度缩小到userId上，不同的userId不需要加锁，相同的userId才需要加锁
        //如果只用userId.toString()的话每次都是new一个新对象，即使值一样也不能锁住同一个用户
        //所以需要加intern，每次都在字符串池里找值一样的字符串地址返回
//        synchronized(userId.toString().intern()) {//仅锁定当前用户

        //创建锁对象(加userId，锁粒度是用户id)
//        SimpleRedisLock lock=new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
//        boolean isLock = lock.tryLock(1200);
        boolean isLock = lock.tryLock();//默认过期时间30秒
        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败，返回错误信息
            return Result.fail("不允许重复下单");
        }
        try {
            //8、返回订单id
            //Spring的事务管理是通过动态代理实现的，如果直接调用CreateVoucherOrder
            // 这里的事务不会自动实现，因为
            //seckillVoucher 直接调用 CreateVoucherOrder，
            //而不是通过 Spring 代理对象调用，因此事务拦截器无法介入
            //所以我们就得创建一个代理对象，然后用代理对象去调用CreateVoucherOrder
            //这样CreateVoucherOrder就会被Spring管理启动事务了
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            return proxy.CreateVoucherOrder(voucherId);
        }finally {
            //释放锁
            lock.unlock();
        }
//        }
    }

    @Transactional
    public Result CreateVoucherOrder(Long voucherId){
        //5、一人一单
        Long userId = UserHolder.getUser().getId();

        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if (count>0){
            //用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        //6、扣减库存
        boolean success = seckillVoucherService.update().
                setSql("stock=stock-1").
                eq("voucher_id", voucherId).gt("stock",0).update();
        if(!success){
            //扣减失败
            return Result.fail("库存不足！");
        }

        //7、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2用户id
        voucherOrder.setUserId(userId);
        //7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        //当前Service的crud方法可以直接用
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
