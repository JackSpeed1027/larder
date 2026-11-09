package com.larder.pipeline.inbox;

/**
 * 一次投递的处理结果。
 *
 * <p>为什么要把结果分这么细：ack 的策略不同。ACCEPTED/ALREADY_* 都可以 ack；
 * CONFLICT 与 UNKNOWN_JOB 属于不可恢复的坏消息，必须 reject 进死信，而不是无限重投。
 */
public enum InboxOutcome {
    /** 首次接收并成功接管 job（job 变 RUNNING、写租约）。 */
    ACCEPTED,
    /** 重复投递，但 job 已被别的执行者持有：ack，不重复接管。 */
    ALREADY_RUNNING,
    /** 重复投递，job 已是终态（成功/失败/取消）：ack，不重复执行。 */
    ALREADY_DONE,
    /** 同 event_id 但 job_id 或 payload_hash 不同：坏消息，进死信。 */
    CONFLICT,
    /** payload 里没有合法 jobId，或 job 行不存在：坏消息，进死信。 */
    UNKNOWN_JOB
}
