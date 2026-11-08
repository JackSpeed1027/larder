package com.larder.content.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

/**
 * S3 兼容对象存储实现（AWS SDK v2，path-style，可指向 MinIO）。
 *
 * <p>不可变保证：
 * <ul>
 *   <li>key 由内容 sha256 派生（{@link ArtifactKeys}），不同内容不会落到同一 key；</li>
 *   <li>写入前 HEAD：已存在则比较 metadata 里的 sha256，不一致直接拒绝，绝不覆盖；</li>
 *   <li>不使用 multipart ETag 充当内容 hash——ETag 在多段上传时不是 sha256（也不是 md5）。</li>
 * </ul>
 *
 * <p>bucket 不存在时自动创建：单机单 bucket 的部署便利；生产可改成 init 容器（V12）。
 */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final String META_SHA256 = "sha256";
    private static final String META_MIME = "mime";

    private final S3Client client;
    private final String bucket;
    private volatile boolean bucketReady;

    public S3ObjectStore(@Value("${larder.storage.endpoint:http://127.0.0.1:9000}") String endpoint,
                         @Value("${larder.storage.region:us-east-1}") String region,
                         @Value("${larder.storage.access-key:}") String accessKey,
                         @Value("${larder.storage.secret-key:}") String secretKey,
                         @Value("${larder.storage.bucket:larder-artifacts}") String bucket) {
        this.bucket = bucket;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Override
    public StoredObject putImmutable(String key, byte[] content, String sha256, String mime) {
        ensureBucket();
        try {
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            String existingSha = head.metadata().get(META_SHA256);
            if (existingSha != null && !existingSha.equals(sha256)) {
                throw new ArtifactConflictException(key, existingSha, sha256);
            }
            // 同 key 同内容：幂等复用，不重写
            return new StoredObject(key, head.versionId(), existingSha == null ? sha256 : existingSha);
        } catch (NoSuchKeyException missing) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(mime)
                            .metadata(java.util.Map.of(META_SHA256, sha256, META_MIME, mime))
                            .build(),
                    RequestBody.fromBytes(content));
            return new StoredObject(key, null, sha256);
        }
    }

    @Override
    public boolean exists(String key) {
        ensureBucket();
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        }
    }

    @Override
    public byte[] get(String key) {
        ensureBucket();
        return client.getObjectAsBytes(builder -> builder.bucket(bucket).key(key)).asByteArray();
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException missing) {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                } else {
                    throw e;
                }
            }
            bucketReady = true;
        }
    }
}
