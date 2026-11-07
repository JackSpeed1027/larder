# ADR-0005 Redis 8 的三许可与选择 AGPLv3

- 状态：ACCEPTED
- 日期：2026-11-06
- 任务：V01-T01
- 证据：`reports/V01-T01/20261106-195106-v01t01/raw/platform/license-redis-8.10.txt`

## 事实

- 选定的 Redis 镜像为 `8.10.3`（digest `sha256:f245f4c8…27f2`，2026-11-05 构建）。
- Redis 8 的 LICENSE 原文写明：Redis Open Source 采用三许可模型，贡献者可择一：(a) Redis Source Available License v2（RSALv2）、(b) Server Side Public License v1（SSPLv1）、(c) **AGPLv3**。
- Redis 7.4 及更早的 7.x 只有 RSALv2/SSPLv1，**不是 OSI 认可的开源许可**。

## 决策

本项目按 **AGPLv3** 使用 Redis 8.10.3，不修改 Redis 源码；Redis 仅作为派生缓存/限流/短期计数使用，绝不承载唯一业务事实（`docs/02` 已有此约束）。

## 理由

1. AGPLv3 是 OSI 认可的开源许可，与本项目其他组件（Apache-2.0/MIT）不存在冲突。
2. 我们要的是"可自托管、许可清晰、能长期用"，7.4 的 RSALv2/SSPLv1 会在"把服务提供给他人"时产生额外限制。
3. 选 8.x 也顺带拿到 8.0 之后的多线程 IO 与内存优化，但这不是主要理由。

## 许可义务（工程口径，不构成法律意见）

- 若**分发修改过的 Redis 源码/镜像**，必须以 AGPLv3 提供对应源码与修改说明；
- 若只是把**未修改**的 Redis 作为服务组件部署，AGPLv3 的网络交互条款针对的是 Redis 程序本身，不会自动把本项目的 Java/Python 源码变成 AGPL；
- 因此：**禁止在项目内 patch Redis 源码后随镜像分发而不开放改动**。若将来必须改 Redis，走单独的 fork 与许可评审。

## 后果

- `compose` 中的 Redis 镜像必须按 digest 固定，不能 `redis:latest`。
- `docs/06` 的 Redis 行需要补一句"AGPLv3，禁改源码随镜像分发"（属文档变更，V01-T02 一起提交）。
- 不因许可问题把缓存降级为"本地 Caffeine 就够"——限流/缓存回源/超时降级仍是目标，`docs/02` 的语义不变。
