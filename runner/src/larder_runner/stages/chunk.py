"""正文分段：稳定、可复现，段落优先，超长段落才硬切。

为什么自己切而不是交给索引层：chunk 的边界和 hash 会进入索引与引用，
必须可复现（同一正文同一切法），否则重建索引会得到完全不同的结果。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass


class ChunkError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str
    start: int
    end: int

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()


def chunk_text(text: str, max_chars: int = 1200) -> list[Chunk]:
    """把正文切成不超过 max_chars 的块；保持原文偏移，便于高亮与引用。"""
    if max_chars < 1:
        raise ChunkError("INVALID_CHUNK_SIZE", "max_chars 必须 >= 1")
    paragraphs = [p for p in text.split("\n\n") if p.strip()]
    if not paragraphs:
        raise ChunkError("EMPTY_CONTENT", "没有可分段的内容")

    chunks: list[Chunk] = []
    cursor = 0
    buffer: list[str] = []
    buffer_start = 0
    buffer_len = 0

    def flush() -> None:
        nonlocal buffer, buffer_len
        if not buffer:
            return
        body = "\n\n".join(buffer)
        chunks.append(Chunk(index=len(chunks), text=body, start=buffer_start, end=buffer_start + len(body)))
        buffer = []
        buffer_len = 0

    for paragraph in paragraphs:
        start = text.find(paragraph, cursor)
        if start < 0:
            start = cursor
        cursor = start + len(paragraph)
        if len(paragraph) > max_chars:
            flush()
            offset = 0
            while offset < len(paragraph):
                piece = paragraph[offset:offset + max_chars]
                chunks.append(Chunk(index=len(chunks), text=piece,
                                    start=start + offset, end=start + offset + len(piece)))
                offset += max_chars
            continue
        if buffer_len + len(paragraph) + (2 if buffer else 0) > max_chars:
            flush()
            buffer_start = start
        if not buffer:
            buffer_start = start
        buffer.append(paragraph)
        buffer_len += len(paragraph) + (2 if len(buffer) > 1 else 0)
    flush()

    if not chunks:
        raise ChunkError("EMPTY_CONTENT", "分段结果为空")
    return chunks
