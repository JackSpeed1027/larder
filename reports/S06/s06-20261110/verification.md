# S06 起步：抓取安全（slice S06.PROXY_GUARD）

- runId：s06-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——禁止环境代理 + SSRF 地址族/重定向用例补齐；**DNS 解析与连接仍非同一目标（未绑定 IP），是下一 slice**。
- 依据：docs/21 §S06、AGENTS §4/§5

## 改动

| 项 | 内容 |
|---|---|
| `fetch()` | 显式 `ProxyHandler({})`：不继承 HTTP(S)_PROXY/系统代理，防止请求被代理改写到内网 |
| 测试 | `tests/stages/fetch/test_fetch_guard.py`：loopback/private/link-local/云元数据/ULA 全部拒绝；混合公网+私网拒绝；`file://` 拒绝；重定向目标重新校验；opener 无代理 |

## 实测

`python -m pytest tests -m "not live"`：**54 passed**（53 + 新 6，去重后）。

## 仍未完成（S06 后续）

1. **DNS 绑定**：`UrlGuard.check` 解析后，urllib 仍会再次解析连接——存在 TOCTOU；需“解析一次、校验全部、连接到已验证 IP（保留 Host/SNI）”；
2. 总耗时/响应大小/重定向次数已有限制，但没有“取消/预算归属”写入任务状态；
3. S06 是 S 阶段总门禁，还需跑全量 verify + 故障注入（ack 后崩溃等）。

## 追加：DNS 解析与连接绑定（S06.PIN_NET）

- `UrlGuard.resolve_for(url)`：解析一次、校验**所有**地址（任一私网即拒绝），返回 `(host, port, pinned_ip)`；
- `_PinnedHTTPConnection` / `_PinnedHTTPSConnection`：`connect()` 直接连 `pinned_ip`；HTTPS 仍用原 host 做 **SNI 与证书校验**；
- `_PinnedHTTPHandler` / `_PinnedHTTPSHandler`：每跳（含重定向）在连接前重新解析+校验；
- `build_opener()` 固定组合：无代理 + 固定 IP + 重定向重校验；
- 单测 5 个：resolve 返回固定 IP、公私混合拒绝、HTTP 连 IP、HTTPS 保留 SNI、opener 无代理。

### 实测

- `pytest`：**59 passed**；
- `verify-run-exec.ps1`：**ALL EXEC CHECKS PASSED**（真实 example.com：`job SUCCEEDED`、`rev_4cb68bbc…`、正文 156 字符），证明固定 IP + SNI 不影响正常抓取。
