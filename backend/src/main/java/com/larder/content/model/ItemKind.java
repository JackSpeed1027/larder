package com.larder.content.model;

/**
 * 条目类型，与 item.kind 的 CHECK 约束一致。
 *
 * <p>V02-T01 只实现 WEB/TEXT 的收录；PDF/VIDEO/AUDIO/TRANSCRIPT 在 V06 的多来源任务里接入，
 * 现在传这些类型会被拒绝，而不是"先收下以后再说"。
 */
public enum ItemKind {
    WEB,
    TEXT,
    PDF,
    VIDEO,
    AUDIO,
    TRANSCRIPT
}
