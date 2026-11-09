package com.larder.content.publication;

/** 发布结果：revisionId + CREATED/REPLAYED（重放不重复写）。 */
public record PublicationResult(String revisionId, String dedupe) {
}
