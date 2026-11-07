"""Render Agent task cards from the reviewed specifications. Does not change task state."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def bullets(values):
    """将明确的任务约束渲染为逐项Markdown列表。"""
    return "\n".join("- " + str(value) for value in values)


def core_card(task):
    """渲染单个核心或实验工作包的Agent输入，保留依赖和文件范围。"""
    return f"""# {task['id']} · {task['title']}

此卡是发给实现Agent的标准任务输入，须附协调者填写的12号文档派工单。当前状态是实施前PLANNED，不能据此假设已存在业务代码。

## 必读与身份

你是{task['leadRole']}角色的实现Agent，协作角色：{', '.join(task['contributors'])}。本任务属于{task['round']}。
先读根AGENTS、docs/09、12、18、19、20，再读本任务涉及的02/03/04/16/17；有前端路径时另读14/15和design规范。
父包较大时仅执行派工单中的2–6h slice，禁止多个窗口各自做整个父包。

## 开工输入

- TASK_ID：{task['id']}；SLICE_ID：由协调者填写。
- BASE_SNAPSHOT / CONTRACT_REV / DESIGN_REV：由协调者提供真实hash，不保留占位符。
- 前置集成任务：{', '.join(task['dependsOn']) or '无；仍需盘点仓库和用户数据'}。
- 前置门禁：{', '.join(task['requiresGates']) or '本任务建立基础，不依赖尚不存在的轮次门禁'}。
- 实际派工目录：由协调者给出绝对路径；不得共享可写checkout。
- 证据：{task['evidencePath']}，runId必须唯一。

## 本任务实现步骤

{bullets(task['instructions'])}

先列现有实现与缺口，再实现正常链路，随后覆盖本卡异常/竞争/恢复边界。遵守不可变版本、权限、幂等、预算和数据保护要求，不另造临时简化架构。

## 最大允许写范围

{bullets(task['paths'])}

实际slice范围必须进一步收窄为以上范围的子集。需要新增公共DTO、迁移、路由、key、依赖或配置时给协调者CR，不能擅自扩大allowlist。
独占资源：{', '.join(task['exclusiveResources'])}。
共享变更规则：{task['sharedChangesPolicy']}

## 可证伪验收

{bullets(task['acceptance'])}

若有对应A01–A28或P/E编号，还须给编号到报告的映射。测试选择能在错误实现下失败的场景，不只断言返回200。

## 交付

{bullets(task['deliverables'])}

计划应用验收入口：`{task['plannedCommand']}`。该命令当前尚未实现，V01/相关阶段必须补齐；不得凭本卡写着命令就报告已运行。
每次生成按20补简单注释；复杂机制补六项说明；交付commentReview。
仅报告TASK_LOCAL_PASS / TASK_LOCAL_FAIL / BLOCKED，附真实命令、退出码、用例/skip、日志、未验证项。
新增文件也进入manifest，不能只交git diff遗漏untracked。协调者在集成树验证后决定全局DONE。

## 不做什么

不越界改公共文件，不清理用户历史或真实数据，不自动commit/push/发布，不发明性能数字，不跳过依赖，不用Mock冒充真实集成，不自行领取下一包。
"""


def frontend_card(task):
    """渲染页面任务卡，区分Mock完成与真实集成。"""
    states = "、".join(task["requiredStates"])
    return f"""# {task['id']} · {task['title']} · 前端Agent标准Prompt

你是Larder前端Agent，完成本派工单指定的{task['id']}阶段。父工作包{task['parentTask']}；轮次{', '.join(task['rounds'])}。多轮页面必须由协调者明确本次是基础版还是最终版。

## 必读和边界

读AGENTS、docs/09/12/14/15/18/19/20、design/tokens.json、design/page-contracts.json和reference-board.html。
核对BASE_SNAPSHOT、CONTRACT_REV、DESIGN_REV、SLICE_ID和允许目录，未提供时先只读核查并向协调者报告。
本页路由/入口：{', '.join(task['routes'])}。依赖页面组件：{', '.join(task['dependsOnPages']) or '无；本任务提供公共底座'}。
启动条件：{task['startCondition']}。
真实集成条件：{task['integrationCondition']}。

## 强制页面行为

{bullets(task['constraints'])}

必须实现的状态：{states}。每状态提供fixture、可访问描述、可行动按钮和退出/恢复路径；不能只有默认成功态。
布局尺寸/颜色/字体/断点必须消费统一token；公共导航、Button、Query keys、HTTP/SSE封装、生成类型不自行复制。
长中文、长URL、无缩略图、空集合、失败响应和超时均需真实渲染。状态不靠颜色单独表达。

## 修改范围

{bullets(task['paths'])}

公共route注册、query key和共享组件需求给C提交CR；C指定单owner后再实现。页面组件中禁止直接fetch、写调度算法或靠localStorage绕过权限。

## 实现顺序

1. 列route/组件树/查询和mutation/状态表；对照现有公共组件，避免重复抽象。
2. 用冻结fixture实现全部状态，keyboard/focus、responsive和reduced-motion同时做。
3. 以生成类型连接真实API，处理HTTP状态、CSRF、并发版本及未知结果；不要吞错误。
4. 按15完成1440×900、390×844及适用暗色/平板截图；文案与关键尺寸写DOM断言。
5. 跑页面真实跨页E2E；依赖缺失只可MOCK_READY，保留尚未验证项。

## 交付和验收

交付页面代码/组件测试/状态fixture/真实API日志/截图manifest/键盘焦点检查/未验证项。
截图来自真实实现时标PRODUCT；本地线框与翻卡示范标DESIGN，不混用两者。
给出14/15要求逐条PASS/FAIL/BLOCKED，不能仅写“页面效果很好”。按12写handoff，不自行改全局DONE。

## 不做什么

不重新设计整站，不用外部品牌素材，不加营销Hero，不自动接受新截图基线，不让Mock数据进入生产fallback，不以动画掩盖请求未成功，不自动公开部署。
"""


def render(check=False):
    """由任务表生成卡片；check模式只读，不修改任务状态。"""
    expected = {}
    for relative, factory in [("tasks/tasks.json", core_card), ("tasks/frontend-tasks.json", frontend_card)]:
        bundle = json.loads((ROOT / relative).read_text(encoding="utf-8"))
        for task in bundle["tasks"]:
            expected[ROOT / task["promptPath"]] = factory(task)
    changed = []
    for path, content in expected.items():
        if not path.exists() or path.read_text(encoding="utf-8") != content:
            changed.append(str(path.relative_to(ROOT)))
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
    if check and changed:
        raise ValueError("Prompt cards out of date: " + ", ".join(changed))
    return {"prompt_cards": len(expected), "changed": len(changed), "mode": "check" if check else "render"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Read-only consistency check")
    args = parser.parse_args()
    print(json.dumps(render(args.check), ensure_ascii=False, indent=2))
