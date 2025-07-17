package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.message.ReusableMessage;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1、校验手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            //2、手机号格式不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //3、生成验证码
        String code= RandomUtil.randomNumbers(6);

        /*//4、保存验证码到session
        session.setAttribute(SystemConstants.CODE,code);*/

        //4、保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5、发送验证码，需要采用阿里的短信，要钱
        log.debug("发送验证码成功,验证码：{}",code);

        //6、返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、校验手机号（手机号密码登录的时候需要重新校验）
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2、如果不符合要求，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        /*//3、校验验证码
        Object cacheCode=session.getAttribute(SystemConstants.CODE);*/

        //3、从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code=loginForm.getCode();
        if(code==null || !code.equals(cacheCode)){
            //4、不一致，报错
            return Result.fail("验证码错误");
        }

        //5、验证码一致，根据手机号查询用户，采用mybatis-plus
        //这条语句相当于select * from tb_user where phone=?
        User user = query().eq(SystemConstants.PHONE,phone).one();

        //6、判断用户是否存在
        if(user==null){
            //7、不存在，创建新用户并保存
            user=createUserWithPhone(phone);
        }

        /*//8、保存用户信息到session
        UserDTO userDTO = new UserDTO(); // 先创建目标对象
        BeanUtils.copyProperties(user, userDTO); // 复制属性
        session.setAttribute(SystemConstants.USER,userDTO);*/

        //8、保存用户信息到redis中
        //8.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();
        //8.2将User对象转化为HashMap存储
        UserDTO userDTO = new UserDTO(); // 先创建目标对象
        BeanUtils.copyProperties(user, userDTO); // 复制属性
        ObjectMapper objectMapper = new ObjectMapper();
        // 先转换为Map<String, Object>
        Map<String, Object> objectMap = objectMapper.convertValue(userDTO, Map.class);

        // 手动转换为Map<String, String>,转为String是因为用的是stringRedisTemplate，只能存放String类型，存放不了Long
        Map<String, String> userMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : objectMap.entrySet()) {
            Object value = entry.getValue();
            userMap.put(entry.getKey(), value != null ? value.toString() : null);
        }

        //8.3存储
        String tokenKey=RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //8.4设置token有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        //9、返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1、创建用户
        User user=new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2、保存用户
        save(user);
        return user;
    }
}
