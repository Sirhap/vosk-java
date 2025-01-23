package com.example.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

@Service
@Slf4j
public class FileCleanupService {
    
    @Value("${spring.servlet.multipart.location:}")
    private String uploadTempDir;
    @Value("${temp.file.directory}")
    private String tempFileDirectory;
    @PostConstruct
    public void init() {
        // 创建临时目录
        File tempDir = new File(uploadTempDir);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        log.info("临时文件目录: {}", tempDir.getAbsolutePath());
    }

    private final Set<Path> trackedFiles = new HashSet<>();

    public void trackFile(Path file) {
        trackedFiles.add(file);
        log.info("添加文件到跟踪列表: {}", file);
    }

    public void cleanupFile(Path file) {
        try {
            if (Files.deleteIfExists(file)) {
                log.info("成功删除文件: {}", file);
                trackedFiles.remove(file);
            } else {
                log.warn("文件不存在或删除失败: {}", file);
            }
        } catch (Exception e) {
            log.error("删除文件时发生错误: {}", file, e);
        }
    }

    @Scheduled(cron = "0 0/2 * * * ?")
    public void scheduledCleanup() {
        log.info("开始定时清理临时文件...");
        cleanup();
        cleanupTomcatTemp();
    }

    private void cleanupTomcatTemp() {
        try {
            File tempDir = new File(uploadTempDir);
            if (tempDir.exists() && tempDir.isDirectory()) {
                Files.walk(tempDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(file -> {
                        if (file.isFile() && file.getName().startsWith("tomcat.")) {
                            if (file.delete()) {
                                log.info("删除Tomcat临时文件: {}", file.getName());
                            }
                        }
                    });
            }
        } catch (IOException e) {
            log.error("清理Tomcat临时文件失败", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("开始清理所有临时文件...");
        Set<Path> failedFiles = new HashSet<>();
        trackedFiles.add(Path.of(tempFileDirectory + "/jave/ffmpeg-aarch64-3.4.0-osx"));
        for (Path file : trackedFiles) {
            try {
                if (!Files.deleteIfExists(file)) {
                    failedFiles.add(file);
                    log.warn("无法删除文件: {}", file);
                } else {
                    log.info("成功删除文件: {}", file);
                }
            } catch (Exception e) {
                failedFiles.add(file);
                log.error("删除文件时发生错误: {}", file, e);
            }
        }

        trackedFiles.removeIf(path -> !failedFiles.contains(path));
        
        // 清理临时目录
        cleanupTomcatTemp(); //todo
    }
} 