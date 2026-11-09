package com.larder.pipeline.reconcile;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 对账调度：默认关闭，`larder.reconcile.enabled=true` 时每 5 秒跑一轮。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "larder.reconcile.enabled", havingValue = "true")
public class ReconcileSchedulingConfig {

    @Component
    @ConditionalOnProperty(name = "larder.reconcile.enabled", havingValue = "true")
    public static class Poller {

        private final JobReconciler reconciler;

        public Poller(JobReconciler reconciler) {
            this.reconciler = reconciler;
        }

        @Scheduled(fixedDelayString = "${larder.reconcile.poll-ms:5000}")
        public void poll() {
            reconciler.runOnce();
        }
    }
}
