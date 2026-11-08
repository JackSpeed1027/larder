package com.larder.identity;

/**
 * 当前登录态视图（只暴露前端需要的字段）。
 *
 * @param username 账号名
 * @param role     角色，V01 只有 ADMIN
 */
public record SessionView(String username, String role) {
}
