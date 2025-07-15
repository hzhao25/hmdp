package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    //因为是自定义的拦截器，不是springboot代理的对象，不能直接注入
    //但是在配置文件里面有configuration注解，可以直接用无参构造把它注入进去
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*//1、获取session
        HttpSession session = request.getSession();*/

        //1、获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        /*//2、获取session中的用户
        Object user = session.getAttribute(SystemConstants.USER);*/

        //2、基于token获取redis中的用户信息
        String key=RedisConstants.LOGIN_USER_KEY+token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

       /* //3、判断用户是否存在
        if(user==null){
            //4、不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }*/

        //3、判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }

        //5、将查询到的Hash数据转为UserDTO对象
        // 将Map<Object, Object>转换为Map<String, Object>
        Map<String, Object> stringObjectMap = new HashMap<>();
        userMap.forEach((k, v) -> stringObjectMap.put(k.toString(), v));

        // 使用ObjectMapper转换为UserDTO
        ObjectMapper objectMapper = new ObjectMapper();
        UserDTO userDTO = objectMapper.convertValue(stringObjectMap, UserDTO.class);

        /*//5、存在，保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);*/

        //6、存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        //7、刷新token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
