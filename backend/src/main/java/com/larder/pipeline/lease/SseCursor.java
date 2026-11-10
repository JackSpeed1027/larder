package com.larder.pipeline.lease;

/**
 * S05：SSE 续传决策（纯函数，便于测试）。
 *
 * <p>背景：snapshot 与 events 之间存在竞态——客户端拿到的 snapshot 可能已经比它的
 * Last-Event-ID 新。规则：
 * <ul>
 *   <li>没有 cursor：全新连接，只发 snapshot；</li>
 *   <li>有 cursor 且日志中有后续事件：回放事件（REPLAY）；</li>
 *   <li>有 cursor、日志无事件、但 job.seq 已前进：历史不可用，必须 RESET（发 reset + 新 snapshot），
 *       不能假装续传；</li>
 *   <li>cursor 不落后（等于或超前，如重启后序号回退）：按全新连接处理，避免重复回放。</li>
 * </ul>
 */
public final class SseCursor {

    public enum Decision {
        FRESH,
        REPLAY,
        RESET
    }

    private SseCursor() {
    }

    public static Decision decide(Long lastEventId, long currentSeq, int replayedEvents) {
        if (lastEventId == null) {
            return Decision.FRESH;
        }
        if (replayedEvents > 0) {
            return Decision.REPLAY;
        }
        if (lastEventId < currentSeq) {
            return Decision.RESET;
        }
        return Decision.FRESH;
    }
}
