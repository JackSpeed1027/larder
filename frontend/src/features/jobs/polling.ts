/** S05：任务轮询的退避与降频策略（纯函数，便于测试）。 */

export const BASE_DELAY_MS = 5_000;
export const MAX_DELAY_MS = 30_000;
export const HIDDEN_DELAY_MS = 60_000;

/**
 * 计算下一次轮询间隔。
 *
 * @param currentMs 当前间隔（失败后退避的起点）
 * @param ok        上一次请求是否成功；失败时指数退避，成功立即回到基础间隔
 * @param hidden    页面是否隐藏；隐藏时降到 60s，避免后台空转
 */
export function nextDelay(currentMs: number, ok: boolean, hidden: boolean): number {
  if (hidden) {
    return HIDDEN_DELAY_MS;
  }
  if (!ok) {
    return Math.min(Math.max(currentMs * 2, BASE_DELAY_MS), MAX_DELAY_MS);
  }
  return BASE_DELAY_MS;
}
