package com.cosmetic.scm.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 附件文件存储：现在存在服务器本地目录（Docker 里是一个卷，备份容器会一起打包）。
 * 以后换对象存储，只需换掉这个类的实现，附件表和接口都不用动。
 */
@Component
@Slf4j
public class FileStorage {

    private final Path root;

    public FileStorage(@Value("${scm.storage.dir:./data/files}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public void write(String key, byte[] bytes) {
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            // CREATE_NEW：同名文件绝不覆盖，附件只增不改
            Files.write(p, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("附件写入失败：" + key, e);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("附件事务回滚后未能清除文件：{}", key, e);
                        }
                    }
                }
            });
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("附件读取失败：" + key, e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("非法的存储路径");
        }
        return p;
    }
}
