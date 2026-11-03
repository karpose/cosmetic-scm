# 备份服务镜像：脚本直接打进镜像，不依赖宿主机目录挂载和文件的可执行权限
FROM mysql:8.0
COPY backup.sh restore.sh /scripts/
RUN chmod 755 /scripts/*.sh
ENTRYPOINT ["/scripts/backup.sh"]
