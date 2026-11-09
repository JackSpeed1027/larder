package com.larder.runner.client;

/** Runner 不可用或返回非 2xx：调用方应把 job 放回重试，而不是标成功。 */
public class RunnerUnavailableException extends RuntimeException {

    public RunnerUnavailableException(String message) {
        super(message);
    }
}
