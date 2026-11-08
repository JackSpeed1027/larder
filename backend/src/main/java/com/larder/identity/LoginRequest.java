package com.larder.identity;

/**
 * 登录请求体。
 *
 * @param username 管理员账号（单用户系统，来自配置）
 * @param password 明文密码，仅用于本次认证，不记录日志
 */
public record LoginRequest(String username, String password) {
}
