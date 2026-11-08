package com.larder.content.storage;

/** 同 key 但内容不同：不可变对象不允许覆写。 */
public class ArtifactConflictException extends ArtifactStorageException {

    public ArtifactConflictException(String key, String existingSha, String incomingSha) {
        super("CORRUPT_ARTIFACT",
                "对象已存在且内容不同，拒绝覆盖: key=" + key
                        + " existing=" + existingSha + " incoming=" + incomingSha);
    }
}
