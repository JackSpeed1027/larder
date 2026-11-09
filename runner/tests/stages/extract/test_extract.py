"""抽取阶段：去脚本/样式、实体解码、标题、空内容报错。"""

from __future__ import annotations

import pytest

from larder_runner.stages.extract import ExtractError, extract

HTML = b"""<html><head><title>  My &amp; Title </title>
<script>alert('x')</script><style>body{color:red}</style></head>
<body><h1>Heading</h1><p>First&nbsp;paragraph.</p><div>Second</div>
<noscript>hidden</noscript></body></html>"""


def test_extracts_title_and_paragraphs_without_scripts() -> None:
    result = extract(HTML, "text/html")
    assert result.title == "My & Title"
    assert "Heading" in result.text
    assert "First paragraph." in result.text
    assert "alert" not in result.text
    assert "color:red" not in result.text
    assert "hidden" not in result.text
    assert result.word_count > 0
    assert len(result.sha256) == 64


def test_plain_text_is_preserved() -> None:
    result = extract(b"line one\n\nline two", "text/plain")
    assert result.text == "line one\n\nline two"
    assert result.title == ""


def test_empty_content_raises() -> None:
    with pytest.raises(ExtractError) as info:
        extract(b"<html><body>   </body></html>", "text/html")
    assert info.value.code == "EMPTY_CONTENT"
