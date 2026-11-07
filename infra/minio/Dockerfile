# MinIO 运行镜像：alpine + 官方源码构建的 Linux 二进制。
# 官方镜像/cdn 均已关闭（ADR-0003），因此镜像内二进制必须与本项目锁定的 sha256 一致；
# 构建前由 scripts/dev/build-minio.ps1 校验 sha256，Dockerfile 本身不联网。
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc

# 契约：二进制由构建脚本放到 build context，文件名固定 minio-linux-amd64
COPY minio-linux-amd64 /usr/local/bin/minio
RUN chmod 0755 /usr/local/bin/minio

EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
