"""分段阶段：大小上限、偏移可回溯、hash 稳定、空内容报错。"""

from __future__ import annotations

import pytest

from larder_runner.stages.chunk import ChunkError, chunk_text


def test_packs_paragraphs_within_limit() -> None:
    text = "aaa\n\nbbb\n\nccc"
    chunks = chunk_text(text, max_chars=10)
    assert len(chunks) >= 2
    assert all(len(chunk.text) <= 10 for chunk in chunks)
    assert "aaa" in chunks[0].text


def test_offsets_point_back_to_original_text() -> None:
    text = "one two\n\nthree four\n\nfive six"
    for chunk in chunk_text(text, max_chars=12):
        assert text[chunk.start:chunk.end] == chunk.text


def test_long_paragraph_is_hard_split() -> None:
    text = "x" * 25
    chunks = chunk_text(text, max_chars=10)
    assert [len(chunk.text) for chunk in chunks] == [10, 10, 5]
    assert "".join(chunk.text for chunk in chunks) == text


def test_hash_is_stable_for_same_text() -> None:
    first = chunk_text("same\n\ntext", max_chars=100)[0]
    second = chunk_text("same\n\ntext", max_chars=100)[0]
    assert first.sha256 == second.sha256
    assert len(first.sha256) == 64


def test_empty_and_invalid_inputs_raise() -> None:
    with pytest.raises(ChunkError):
        chunk_text("   ")
    with pytest.raises(ChunkError):
        chunk_text("abc", max_chars=0)
