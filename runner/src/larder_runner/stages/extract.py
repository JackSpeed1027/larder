"""正文抽取：HTML → 纯文本（标题 + 段落），纯文本原样规范化。

刻意不用 readability 类库：V02 只需要"能读的正文 + 可复现"，复杂版面在后续版本增强。
抽取规则透明、可测，失败时明确报 EMPTY_CONTENT，而不是返回空字符串假装成功。
"""

from __future__ import annotations

import hashlib
import html
import re
from dataclasses import dataclass
from html.parser import HTMLParser

SKIP_TAGS = {"script", "style", "noscript", "template", "svg"}
BLOCK_TAGS = {
    "p", "div", "section", "article", "li", "br", "tr", "h1", "h2", "h3",
    "h4", "h5", "h6", "blockquote", "pre", "table", "ul", "ol",
}
WHITESPACE = re.compile(r"[ \t\u00a0]+")
BLANK_LINES = re.compile(r"\n{3,}")


class ExtractError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Extracted:
    title: str
    text: str

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()

    @property
    def word_count(self) -> int:
        return len(self.text.split())


class _TextCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.title_parts: list[str] = []
        self._skip_depth = 0
        self._in_title = False

    def handle_starttag(self, tag: str, attrs) -> None:  # noqa: D102
        if tag in SKIP_TAGS:
            self._skip_depth += 1
        elif tag == "title":
            self._in_title = True
        elif tag in BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:  # noqa: D102
        if tag in SKIP_TAGS and self._skip_depth:
            self._skip_depth -= 1
        elif tag == "title":
            self._in_title = False
        elif tag in BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:  # noqa: D102
        if self._skip_depth:
            return
        if self._in_title:
            self.title_parts.append(data)
        else:
            self.parts.append(data)


def _normalize(raw: str) -> str:
    text = html.unescape(raw)
    text = WHITESPACE.sub(" ", text)
    lines = [line.strip() for line in text.split("\n")]
    text = "\n".join(lines)
    return BLANK_LINES.sub("\n\n", text).strip()


def extract(body: bytes, content_type: str) -> Extracted:
    """按 content-type 抽取正文；空内容抛 ExtractError。"""
    raw = body.decode("utf-8", errors="replace")
    if content_type in ("text/html", "application/xhtml+xml"):
        parser = _TextCollector()
        parser.feed(raw)
        title = _normalize("".join(parser.title_parts))
        text = _normalize("".join(parser.parts))
    else:
        title = ""
        text = _normalize(raw)
    if not text:
        raise ExtractError("EMPTY_CONTENT", "抽取后正文为空")
    return Extracted(title=title, text=text)
