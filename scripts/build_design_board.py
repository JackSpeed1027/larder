"""Generate original local specification wireframes and an isolated flip interaction demo."""
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "design"


class Drawing:
    def __init__(self, title):
        """创建一张本地原创SVG设计图，不代表真实产品截图。"""
        self.parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="900" viewBox="0 0 1440 900" role="img" aria-label="{html.escape(title)}"><title>{html.escape(title)}</title>',
                      '<style>text{font-family:Segoe UI,Microsoft YaHei,sans-serif;fill:#182230}.muted{fill:#526173}.blue{fill:#1D4ED8}.white{fill:white}</style>']
        self.rect(0, 0, 1440, 900, "#F7F8FA", radius=0)

    def rect(self, x, y, width, height, fill="#FFFFFF", stroke="#D8DEE8", radius=8):
        """添加一个边界可见的布局矩形。"""
        self.parts.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" fill="{fill}" stroke="{stroke}"/>')

    def text(self, x, y, text, size=16, cls="", weight=400):
        """转义文本后添加中文标签，避免原文变成SVG标记。"""
        self.parts.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" class="{cls}">{html.escape(text)}</text>')

    def line(self, x1, y1, x2, y2):
        """绘制水平分隔线。"""
        self.parts.append(f'<path d="M{x1} {y1}H{x2}" stroke="#D8DEE8"/>')

    def button(self, x, y, text, width=112, primary=False):
        """绘制统一高度按钮线框，仅用于规范展示。"""
        self.rect(x, y, width, 40, "#1D4ED8" if primary else "#FFFFFF")
        self.text(x + 16, y + 26, text, 14, "white" if primary else "")

    def shell(self, active="资料库", compact=False):
        """绘制固定导航与顶栏，标注设计稿身份。"""
        width = 64 if compact else 216
        self.rect(0, 0, width, 900, "#FFFFFF", radius=0)
        self.rect(width, 0, 1440-width, 56, "#FFFFFF", radius=0)
        self.text(18, 35, "L" if compact else "Larder  拾藏", 21, weight=600)
        labels = ["资料库", "搜索", "Agent", "学习", "任务", "集合", "标签"]
        for index, label in enumerate(labels):
            y = 92 + 48 * index
            if label == active:
                self.rect(8, y-24, width-16, 40, "#E8EEFC", "none", 6)
            self.text(20, y+1, label[0] if compact else label, 14, "blue" if label==active else "")
        self.text(20, 810, "回" if compact else "回收站", 14, "muted")
        self.text(20, 856, "设" if compact else "设置", 14, "muted")
        self.text(width+24, 35, "DESIGN  /  本地原创线框 · 非已实现产品", 13, "muted")
        self.button(1296, 8, "保存内容", 120, True)

    def save(self, filename):
        """保存本任务生成的SVG设计资产。"""
        (DESIGN/filename).write_text("\n".join(self.parts)+"</svg>\n", encoding="utf-8")


def drawings():
    """生成资料库、阅读器、Agent审批和复习四张线框。"""
    d=Drawing("F01 资料库线框"); d.shell()
    d.text(240, 112, "资料库", 26, weight=600); d.text(240, 144, "保存、阅读与组织你的资料。示例数据，不代表实际数量。", 14, "muted")
    for x, label, w in [(240,"类型：全部",140),(392,"状态：全部",140),(544,"标签",112),(668,"集合",112),(1200,"最近保存",216)]:
        d.button(x,176,label,w)
    d.rect(240, 240, 1176, 504)
    titles=[("Outbox 与可靠异步任务", "网页 · engineering.example · 今天", "可阅读"), ("构建可信的个人知识索引", "PDF · 示例资料 · 今天", "索引中"), ("工具调用与人工审批边界", "粘贴文本 · 昨天", "可阅读"), ("学习：间隔复习算法", "网页 · study.example · 昨天", "待处理"), ("上下文压缩与事实快照", "字幕 · 示例视频 · 11月3日", "需处理")]
    for i,(title,meta,status) in enumerate(titles):
        y=260+i*96
        if i:d.line(264,y-12,1392,y-12)
        d.rect(264,y+4,40,40,"#EFF3F8")
        d.text(276,y+30,str(i+1),15,"muted")
        d.text(324,y+20,title,18,weight=600);d.text(324,y+47,meta,13,"muted");d.text(324,y+70,"工程实践   /   待读",12,"blue")
        d.rect(1240,y+10,96,28,"#EFF3F8",radius=14);d.text(1256,y+29,status,12);d.text(1372,y+29,"•••",15)
    d.text(240,785,"每页 20 条  ·  按后端游标翻页，不展示虚假总数",13,"muted")
    d.button(1200,760,"上一页",100);d.button(1312,760,"下一页",104)
    d.text(240,850,"约束：侧栏216 / 顶栏56 / 行高至少88 / 主内容padding24 / error与empty不同",14,"muted")
    d.save("wireframe-library.svg")

    d=Drawing("F03 阅读器线框");d.shell(compact=True)
    d.text(88,92,"← 返回搜索结果",14,"blue");d.text(842,92,"Aa   笔记   AI辅助   •••",14,"muted")
    d.rect(232,120,720,46,"#FEFCE8");d.text(248,149,"正在查看检索时的版本  ·  查看最新版本 →",14)
    d.text(232,214,"可靠的异步任务，如何安全恢复？",28,weight=600)
    d.text(232,250,"保存版本 r3  ·  原文来源 engineering.example  ·  阅读设置可调整",13,"muted")
    d.line(232,275,952,275)
    body=["一次请求跨过数据库、消息队列和外部调用，失败可能发生在任意一步。", "系统需要记录已经发生的事实，并明确哪些结果仍然未知。", "", "1. 先持久接管，再确认消息", "消息消费者先在事务中登记接管记录和后续对账时间，再发送确认。", "如果进程此时退出，新的工作进程仍可从数据库找回未完成的任务。", "", "2. 旧任务不能覆盖新结果", "租约令牌保护执行权，处理代次保护用户最新的处理请求。", "两个条件都通过后，才能把候选版本发布为当前正文。", "", "划选文字可以记笔记、解释或制卡，引用始终绑定这个保存版本。"]
    for i,t in enumerate(body):d.text(232,304+i*39,t,18,weight=600 if t.startswith(('1.','2.')) else 400)
    d.rect(1096,56,344,844,radius=0);d.text(1116,95,"笔记     AI     信息",16,weight=600)
    d.line(1112,115,1424,115);d.text(1116,156,"本篇笔记",18,weight=600)
    d.rect(1112,180,312,160,"#F7F8FA");d.text(1128,210,"持久接管 ≠ 已执行完成",15,weight=600)
    d.text(1128,242,"这里保存了阅读时的关键问题。",14,"muted");d.text(1128,278,"引用：r3 / 片段 12 →",13,"blue")
    d.text(1116,385,"AI 生成内容需保留来源引用。",14,"muted");d.text(1116,418,"失败不影响正文继续阅读。",14,"muted")
    d.text(232,850,"正文最大720px / 18px / 行高1.8；小屏证据栏改drawer",14,"muted")
    d.save("wireframe-reader.svg")

    d=Drawing("F06 Agent工作台与F07审批线框");d.shell("Agent")
    d.rect(216,56,240,844,radius=0);d.button(232,80,"新会话",208)
    d.text(236,166,"比较三篇文章的结论",15,weight=600);d.text(236,198,"今天 · 等待确认",13,"muted")
    d.text(236,262,"整理学习笔记",15);d.text(236,294,"昨天 · 已完成",13,"muted")
    d.text(480,99,"资料问答与整理",22,weight=600);d.text(480,129,"范围：已选 3 篇资料   ·   记忆已启用",13,"muted")
    d.rect(480,158,584,84,"#E8EEFC");d.text(496,190,"请比较这些资料，并把共同结论整理成笔记。",16)
    d.rect(480,270,584,92);d.text(496,302,"已读取 3 篇资料",16,weight=600);d.text(496,334,"工具结果可展开查看；引用绑定保存版本。",14,"muted")
    d.text(480,407,"三篇资料都强调：状态必须先持久化，",18);d.text(480,443,"再向调用方确认。差异在于外部效果的恢复边界。",18)
    d.text(480,483,"[1] 可靠任务  [2] 工具审批  [3] 恢复协议",14,"blue")
    d.rect(480,526,584,110,"#FEFCE8");d.text(496,558,"需要你批准一次修改",16,weight=600)
    d.text(496,588,"拟新增一条笔记。批准前不会写入资料库。",14)
    d.button(900,580,"查看提议",148)
    d.rect(480,744,584,112);d.text(496,778,"输入下一步任务…",15,"muted");d.text(496,829,"预算与资料范围可查看",13,"muted");d.button(936,800,"发送",112,True)
    d.rect(1088,56,352,844,radius=0);d.text(1112,101,"确认修改",21,weight=600);d.text(1112,140,"目标：学习笔记 / 版本 4",14,"muted")
    d.rect(1104,174,320,92,"#FEF3F2");d.text(1120,204,"修改前",13,"muted");d.text(1120,238,"尚无总结段落",15)
    d.rect(1104,284,320,158,"#F0FDF4");d.text(1120,316,"修改后",13,"muted");d.text(1120,354,"新增：可靠执行的三个约束…",15)
    d.text(1112,492,"本提议有有效期；目标变化需重提。",13,"muted")
    d.text(1112,532,"□ 我已核对本次修改范围",14)
    d.button(1104,580,"拒绝",96);d.button(1212,580,"批准本次修改",212,True)
    d.text(1112,712,"不展示模型隐藏思维。",14,"muted");d.text(1112,744,"不自动批准后续操作。",14,"muted")
    d.save("wireframe-agent.svg")

    d=Drawing("F10 翻卡复习线框");d.shell("学习")
    d.text(428,112,"今日复习",26,weight=600);d.text(428,148,"示例进度 3 / 12  ·  问答卡  ·  AI关闭仍可学习",14,"muted")
    d.rect(428,196,800,420);d.text(460,239,"问题面  ·  Space / 点击显示答案",14,"muted")
    d.text(492,350,"为什么消息确认后，任务仍需要对账？",27,weight=600)
    d.text(492,401,"先自己回想，再翻面核对。",17,"muted")
    d.text(460,568,"来源：可靠异步任务 / 保存版本 r3 →",14,"blue")
    d.button(696,648,"显示答案 ↻",264,True)
    d.text(428,737,"答案揭示后才显示评分：",14,"muted")
    for x,text in [(428,"1  重来"),(636,"2  困难"),(844,"3  良好"),(1052,"4  轻松")]:d.button(x,760,text,176)
    d.text(428,850,"280ms翻面 / 减少动画时直接切面 / 长背诵卡用展开 / 评分以服务器成功为准",14,"muted")
    d.save("wireframe-review.svg")


def board():
    """生成离线参考板及无后端副作用的翻卡交互示范。"""
    references=json.loads((DESIGN/"references.json").read_text(encoding="utf-8"))["references"]
    links="".join(f'<li><strong>{r["id"]}</strong> — {r["purpose"]} <a href="{r["source"]}" target="_blank" rel="noopener noreferrer">官方说明</a> · <a href="{r["image"]}" target="_blank" rel="noopener noreferrer">官方截图</a></li>' for r in references)
    figures="".join(f'<figure><figcaption>{title}</figcaption><img src="{filename}" alt="{title}" width="1440" height="900" loading="lazy"></figure>' for filename,title in [("wireframe-library.svg","F01 资料库：稳定导航、紧凑列表、明确处理状态"),("wireframe-reader.svg","F03 阅读器：正文优先、固定版本引用、可收起证据栏"),("wireframe-agent.svg","F06 / F07 Agent与审批：真实操作可追踪、修改需确认"),("wireframe-review.svg","F10 复习：先回想、再翻面、服务端确认评分")])
    content='''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Larder 设计基线与翻卡示范</title>
<style>
*{box-sizing:border-box}[hidden]{display:none!important}body{margin:0;background:#F7F8FA;color:#182230;font:16px/1.7 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1488px;margin:auto;padding:32px 24px 64px}h1{font-size:30px;line-height:1.3;margin:12px 0}h2{font-size:22px;margin-top:36px}p{max-width:1050px}a{color:#1D4ED8}a:focus-visible,button:focus-visible,[tabindex]:focus-visible{outline:2px solid #1D4ED8;outline-offset:3px}.tag{display:inline-block;padding:3px 10px;border-radius:999px;background:#E8EEFC;color:#1D4ED8;font-size:12px}.notice{border-left:4px solid #1D4ED8;background:white;padding:16px 20px}.specs{display:flex;gap:12px;flex-wrap:wrap}.specs span{background:white;border:1px solid #D8DEE8;padding:8px 12px;border-radius:6px;font-size:14px}figure{margin:24px 0 40px;background:white;border:1px solid #D8DEE8;border-radius:8px;overflow:hidden}figcaption{padding:14px 18px;font-weight:600;border-bottom:1px solid #D8DEE8}figure img{display:block;width:100%;height:auto}li{margin:12px 0}button{font:inherit;border:1px solid #D8DEE8;border-radius:6px;background:white;color:#182230;min-height:44px;padding:8px 18px;cursor:pointer}button.primary{background:#1D4ED8;color:white;border-color:#1D4ED8}button:disabled{opacity:.45;cursor:not-allowed}.demo{max-width:800px;margin:24px auto;border:1px solid #D8DEE8;border-radius:12px;padding:24px;background:white}.demo-head{display:flex;justify-content:space-between;gap:16px;font-size:14px;color:#526173}.flip-stage{perspective:1200px;margin:20px 0}.flip-card{display:grid;transform-style:preserve-3d;transition:transform 280ms ease;min-height:280px}.flip-card.flipped{transform:rotateY(180deg)}.face{grid-area:1/1;backface-visibility:hidden;border:1px solid #D8DEE8;border-radius:8px;background:#F7F8FA;padding:32px;display:flex;flex-direction:column;justify-content:center;gap:20px}.face h3{font-size:22px;line-height:1.6;margin:0}.face p{margin:0}.face small{color:#526173}.back{transform:rotateY(180deg);background:#F0FDF4}.actions{display:flex;justify-content:center;gap:12px;flex-wrap:wrap}.grades{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:20px}.feedback{min-height:28px;color:#526173;font-size:14px}.fine{font-size:13px;color:#526173}@media(max-width:600px){main{padding:24px 16px}h1{font-size:25px}.demo{padding:16px}.face{padding:20px}.grades{grid-template-columns:repeat(2,1fr)}.face h3{font-size:20px}}@media(prefers-reduced-motion:reduce){.flip-card{transition:none;transform:none!important}.back{transform:none}.face[aria-hidden=true]{visibility:hidden}}
</style></head><body><main>
<span class="tag">DESIGN SPECIFICATION · 2026-11-05</span><h1>Larder 拾藏 · 前端执行基线</h1>
<p class="notice">这是一份给开发Agent使用的原创线框与交互示范，不是已完成的React产品。尺寸、状态、接口和验收以docs/14、15及design/tokens.json为准。四张线框可离线查看，外部参考通过官方链接打开。</p>
<div class="specs"><span>侧栏 216px</span><span>顶栏 56px</span><span>正文 720px / 18px / 1.8</span><span>主色 #1D4ED8</span><span>翻面 280ms</span><span>默认分页 20，最大 50</span></div>
<h2>翻卡交互示范</h2><p>问答与挖空卡翻面；长背诵卡与时间戳卡展开。这里只演示显示状态，不发送请求、不改变复习记录。生产实现评分必须等待Java确认，网络结果未知时查询原请求。</p>
<section class="demo" aria-label="翻卡交互示范" tabindex="0"><div class="demo-head"><span>问答卡 · 设计示例</span><span id="state-label">问题面</span></div>
<div class="flip-stage"><div id="flip-card" class="flip-card"><div id="front" class="face" aria-hidden="false"><small>先回想，再核对</small><h3>为什么消息确认后，任务仍需要对账？</h3><p>提示：区分“持久接管”与“执行完成”。</p><small>来源：可靠异步任务 / r3 / 片段12</small></div><div id="back" class="face back" aria-hidden="true" inert><small>答案面</small><h3>确认接管后，进程仍可能在执行前崩溃。</h3><p>对账扫描持久任务记录，复用稳定runId继续查询或执行；提交时仍检查租约令牌与处理代次。</p><small>引用绑定原版本。答案可核对后再评分。</small></div></div></div>
<div class="actions"><button id="reveal" class="primary" type="button">显示答案 · Space</button><button id="reset" type="button">复位示例</button></div>
<div class="grades" hidden aria-label="答案揭示后可选择评分"><button disabled data-grade="0">1 重来</button><button disabled data-grade="3">2 困难</button><button disabled data-grade="4">3 良好</button><button disabled data-grade="5">4 轻松</button></div>
<p id="feedback" class="feedback" role="status" aria-live="polite">翻面后显示可评分状态；减少动画设置下直接切换。</p></section>
<h2>页面线框</h2>__FIGURES__
<h2>官方界面参考</h2><p class="fine">核验日期2026-11-05。只借鉴信息层级与侧栏交互；不复制品牌、代码或外站功能范围。未打包第三方截图。链接内容可能随官方版本变化，本地规范是稳定依据。</p><ul>__LINKS__</ul>
<h2>Agent开工规则</h2><p>领取具体Fxx任务与独立目录，锁定designRev；消费公共组件，不重画导航。每页覆盖loading/empty/error/授权及特有冲突状态。Mock通过仅标MOCK_READY，真实API与浏览器验证后交付协调者集成。</p>
</main><script>
const card=document.getElementById('flip-card');const front=document.getElementById('front');const back=document.getElementById('back');const reveal=document.getElementById('reveal');const reset=document.getElementById('reset');const feedback=document.getElementById('feedback');const label=document.getElementById('state-label');const grades=[...document.querySelectorAll('[data-grade]')];let answer=false;let busy=false;let revealed=false;let timer;
// 此示范只有显示状态；不调用API、不提交复习评分。
function settle(){document.querySelector('.grades').hidden=!revealed;busy=false;reveal.disabled=false;reset.disabled=false;front.setAttribute('aria-hidden',String(answer));back.setAttribute('aria-hidden',String(!answer));front.inert=answer;back.inert=!answer;grades.forEach(button=>button.disabled=!revealed);label.textContent=answer?'答案面':'问题面';reveal.textContent=answer?'再看题目':'显示答案 · Space';feedback.textContent=answer?'答案已揭示，可选择评分（仅设计演示，不写入数据）。':'问题面；翻面不产生复习记录。';}
function flip(){if(busy)return;busy=true;answer=!answer;revealed=revealed||answer;reveal.disabled=true;reset.disabled=true;grades.forEach(button=>button.disabled=true);label.textContent='切面中';card.classList.toggle('flipped',answer);clearTimeout(timer);timer=setTimeout(settle,matchMedia('(prefers-reduced-motion: reduce)').matches?0:300);}
reveal.addEventListener('click',flip);reset.addEventListener('click',()=>{clearTimeout(timer);answer=false;revealed=false;card.classList.remove('flipped');settle();feedback.textContent='已复位设计示例，未提交任何评分。';});grades.forEach(button=>button.addEventListener('click',()=>{feedback.textContent='演示：选择 '+button.textContent+'。生产实现需同requestId提交，并等待服务器确认。';}));document.querySelector('.demo').addEventListener('keydown',event=>{if(event.isComposing||event.target.closest('input,textarea,select,button,a'))return;if(event.code==='Space'){event.preventDefault();flip();}else if(revealed&&!busy&&['1','2','3','4'].includes(event.key)){grades[Number(event.key)-1].click();}});
</script></body></html>'''
    (DESIGN/"reference-board.html").write_text(content.replace("__FIGURES__",figures).replace("__LINKS__",links),encoding="utf-8")


if __name__=="__main__":
    drawings()
    board()
    print("Generated 4 original SVG wireframes and 1 local HTML design/flip demo.")
