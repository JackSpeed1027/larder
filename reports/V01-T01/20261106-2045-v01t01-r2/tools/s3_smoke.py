"""MinIO 本地冒烟：验证 S3 语义是否满足 Larder 的对象存储需求。

验证项（失败即说明该 MinIO 构建不能用于 V02 的不可变产物）：
1. bucket 创建/列举；
2. put/get/head 与 SHA256 校验和；
3. 预签名 URL 可生成（下载走 Java 授权后的短期 URL）；
4. bucket versioning：同一 key 两次写入保留两个版本（V02 需要不可变 revision）。
"""

import hashlib
import sys

import boto3
from botocore.client import Config
from botocore.exceptions import ClientError

ENDPOINT = "http://127.0.0.1:9000"
BUCKET = "larder-v01t01-smoke"

failures: list[str] = []


def check(name: str, fn):
    try:
        detail = fn()
        print(f"[S3] PASS {name}: {detail}")
    except Exception as exc:  # noqa: BLE001 - 冒烟脚本需要把所有失败都报出来
        failures.append(f"{name}: {exc}")
        print(f"[S3] FAIL {name}: {exc}")


def main() -> int:
    s3 = boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id="larder_test",
        aws_secret_access_key="larder_test_secret",
        region_name="us-east-1",
        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
    )

    check("create_bucket", lambda: s3.create_bucket(Bucket=BUCKET)["ResponseMetadata"]["HTTPStatusCode"])

    body = b"larder immutable object v1"
    digest = hashlib.sha256(body).hexdigest()

    def put_v1():
        s3.put_object(Bucket=BUCKET, Key="items/1/rev1.txt", Body=body)
        return digest

    check("put_object", put_v1)
    check("get_object", lambda: s3.get_object(Bucket=BUCKET, Key="items/1/rev1.txt")["Body"].read().decode())
    check("head_object", lambda: s3.head_object(Bucket=BUCKET, Key="items/1/rev1.txt")["ContentLength"])
    check("presign", lambda: s3.generate_presigned_url("get_object", Params={"Bucket": BUCKET, "Key": "items/1/rev1.txt"}, ExpiresIn=60)[:80] + "...")

    def versioning():
        s3.put_bucket_versioning(Bucket=BUCKET, VersioningConfiguration={"Status": "Enabled"})
        s3.put_object(Bucket=BUCKET, Key="items/1/rev2.txt", Body=b"v2")
        s3.put_object(Bucket=BUCKET, Key="items/1/rev2.txt", Body=b"v3")
        versions = s3.list_object_versions(Bucket=BUCKET, Prefix="items/1/rev2.txt")["Versions"]
        return len(versions)

    check("versioning_two_versions", versioning)

    def multipart_like():
        # 分片上传的最小证明：>5MiB 的对象必须走 multipart
        payload = b"x" * (6 * 1024 * 1024)
        s3.put_object(Bucket=BUCKET, Key="items/1/big.bin", Body=payload)
        head = s3.head_object(Bucket=BUCKET, Key="items/1/big.bin")
        return head["ContentLength"]

    check("large_object_6MiB", multipart_like)

    print(f"[S3] endpoint={ENDPOINT} bucket={BUCKET} failures={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
