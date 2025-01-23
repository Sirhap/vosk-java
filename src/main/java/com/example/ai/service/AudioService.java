package com.example.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.PostConstruct;
import javax.sound.sampled.UnsupportedAudioFileException;

import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.Encoder;

@Service
@Slf4j
public class AudioService {
    private static Model model;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Environment env;
    @Autowired
    private FileCleanupService fileCleanupService;

    @PostConstruct
    private void loadModel() {
        String osName = Optional.ofNullable(System.getProperty("os.name")).orElse("mac").toLowerCase();
        String voskNativeDir;
        String libName;
        
        if (osName.contains("mac")) {
            voskNativeDir = "vosk-osx-0.3.38";
            libName = "libvosk.dylib";
        } else if (osName.contains("win")) {
            voskNativeDir = "vosk-win64-0.3.38";
            libName = "libvosk.dll";
        } else {
            throw new RuntimeException("不支持的操作系统: " + osName);
        }
        
        try {

            String voskNativeLibPath = env.getProperty("vosk.native.lib.path");
            String voskModelPath = env.getProperty("vosk.model.path");
            log.info("开始加载native库路径：: {}", voskNativeLibPath);
            // 获取native库路径
            if (! Files.exists(Paths.get(voskNativeLibPath))) {
                throw new RuntimeException("找不到native库: " + voskNativeLibPath);
            }

            // 加载native库
            System.load(voskNativeLibPath);
            log.info("native库加载成功");

            Path path = Paths.get(voskModelPath);

            // 选择模型类型
            log.info("使用模型: " + path.getFileName());
            
            // 获取模型目录

            if (! Files.exists(Paths.get(voskModelPath))) {
                throw new RuntimeException("找不到模型目录: " + voskModelPath);
            }
            //File modelDir = modelResource.getFile();

            log.info("正在加载模型，路径: " + voskModelPath);
            model = new Model(voskModelPath);
            log.info("模型加载成功");
            
        } catch (Exception e) {
            log.error("加载模型失败: " + e.getMessage(), e);
            throw new RuntimeException("模型加载失败", e);
        }
    }

    private static class AudioConversionResult implements AutoCloseable {
        final AudioInputStream audioInputStream;
        final File wavFile;
        final File sourceFile;

        AudioConversionResult(AudioInputStream audioInputStream, File wavFile, File sourceFile) {
            this.audioInputStream = audioInputStream;
            this.wavFile = wavFile;
            this.sourceFile = sourceFile;
        }
        
        @Override
        public void close() throws IOException {
            if (audioInputStream != null) {
                audioInputStream.close();
            }
        }
    }

    public AudioConversionResult convertToPCM(MultipartFile file) throws IOException, UnsupportedAudioFileException {
        // 创建临时的源文件和WAV文件

        File tempFile = File.createTempFile("audio_source", ".webm");
        File wavFile = File.createTempFile("audio", ".wav");
        
        try {
            // 将MultipartFile保存为临时文件
            file.transferTo(tempFile);

            // 跟踪临时文件以便清理
            fileCleanupService.trackFile(tempFile.toPath());
            fileCleanupService.trackFile(wavFile.toPath());

            // 使用JAVE转换为WAV
            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("pcm_s16le");
            audio.setBitRate(16000);
            audio.setChannels(1);
            audio.setSamplingRate(16000);
            
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("wav");
            attrs.setAudioAttributes(audio);
            
            Encoder encoder = new Encoder();
            encoder.encode(new MultimediaObject(tempFile), wavFile, attrs);



            // 返回音频流和所有相关文件
            return new AudioConversionResult(AudioSystem.getAudioInputStream(wavFile), wavFile, tempFile);
            
        } catch (Exception e) {
            // 如果处理失败，清理所有临时文件
            fileCleanupService.cleanupFile(tempFile.toPath());
            fileCleanupService.cleanupFile(wavFile.toPath());
            log.error(e.getMessage(), e);
            throw new IOException("音频转换失败: " + e.getMessage(), e);
        }
    }

    private String addPunctuation(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 1. 处理常见的句尾语气词（扩充）
        text = text.replaceAll("(吗|呢|啊|吧|么|哦|呀|哈|呵|嘛|噢|哇|诶|嗯|呐|喔|咯|啦|呗|咧|嘞)(?=\\s|$)", "$1。");
        
        // 2. 处理问句关键词（整句判断）
        if (text.matches(".*?(为什么|怎么|什么|谁|哪里|如何|几|多少).*?") && !text.matches(".*[。！？]$")) {
            text += "？";
        }
        
        // 3. 处理转折词和连接词（作为分句依据）
        String[] segments = text.split("(那么|所以|因此|但是|不过|然后|况且|并且|而且|因为|要是|如果|虽然|尽管|否则|此外|总之|另外|首先|其次|最后|例如|比如|即使|无论|只要|既然|于是|以及|或者)");
        if (segments.length > 1) {
            StringBuilder newText = new StringBuilder();
            String[] connectors = text.split("[^(那么|所以|因此|但是|不过|然后|况且|并且|而且|因为|要是|如果|虽然|尽管|否则|此外|总之|另外|首先|其次|最后|例如|比如|即使|无论|只要|既然|于是|以及|或者)]+");
            
            for (int i = 0; i < segments.length; i++) {
                newText.append(segments[i]);
                if (i < segments.length - 1 && i < connectors.length) {
                    newText.append("，").append(connectors[i]);
                }
            }
            text = newText.toString();
        }
        
        // 4. 处理感叹句（整句判断）
        if (text.matches(".*?(太|真是|太棒|完美|糟糕|真棒|真好|太好|极了).*?") && !text.matches(".*[。！？]$")) {
            text += "！";
        }
        
        // 5. 基于语义单位添加逗号（避免过度断句）
        StringBuilder result = new StringBuilder();
        String[] phrases = text.split("\\s+");
        for (int i = 0; i < phrases.length; i++) {
            result.append(phrases[i]);
            if (i < phrases.length - 1 && phrases[i].length() + phrases[i + 1].length() > 12 
                && !phrases[i].matches(".*[，。！？、]$")
                && !isConnectiveWord(phrases[i] + phrases[i + 1])) {
                result.append("，");
            } else if (i < phrases.length - 1) {
                result.append(" ");
            }
        }
        
        // 6. 处理句尾（确保句子有结束标点）
        String finalText = result.toString().replaceAll("，$", "").replaceAll("\\s+", "");
        if (!finalText.matches(".*[。！？]$")) {
            finalText += "。";
        }
        
        return finalText;
    }

    // 辅助方法：判断是否是连接词（避免在连接词处断句）
    private boolean isConnectiveWord(String text) {
        String[] connectiveWords = {
            "而且", "并且", "因为", "所以", "但是", "不过", "然后", "如果", 
            "虽然", "要是", "即使", "只要", "以及", "或者", "就是", "的",
            "地", "得", "和", "与", "或"
        };
        
        for (String word : connectiveWords) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public String processAudioFile(MultipartFile file) throws IOException, UnsupportedAudioFileException {
        try (AudioConversionResult conversionResult = convertToPCM(file)) {
            AudioInputStream audioInputStream = conversionResult.audioInputStream;
            File wavFile = conversionResult.wavFile;
            File tempFile = conversionResult.sourceFile;  // 使用 AudioConversionResult 中保存的原始临时文件

            // 确保音频格式为 PCM_SIGNED，16kHz
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            AudioInputStream convertedAudioStream = AudioSystem.getAudioInputStream(format, audioInputStream);

            try {
                // 创建识别器
                try (Recognizer recognizer = new Recognizer(model, 16000)) {
                    byte[] buffer = new byte[4096];
                    StringBuilder result = new StringBuilder();
                    String lastText = "";

                    int bytesRead;
                    while ((bytesRead = convertedAudioStream.read(buffer)) != -1) {
                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            String partialResult = recognizer.getResult();
                            String text = objectMapper.readTree(partialResult).get("text").asText().trim();
                            if (!text.isEmpty()) {
                                if (!lastText.isEmpty() && shouldAddSpace(lastText, text)) {
                                    result.append(" ");
                                }
                                result.append(text.replaceAll("\\s+", " "));
                                lastText = text;
                            }
                        }
                    }
                    
                    // 处理最终结果
                    String finalResult = recognizer.getFinalResult();
                    String finalText = objectMapper.readTree(finalResult).get("text").asText().trim();
                    if (!finalText.isEmpty()) {
                        if (!lastText.isEmpty() && shouldAddSpace(lastText, finalText)) {
                            result.append(" ");
                        }
                        result.append(finalText.replaceAll("\\s+", " "));
                    }

                    // 添加标点符号
                    String textWithPunctuation = addPunctuation(result.toString().trim());
                    
                    return textWithPunctuation;
                } finally {
                    // 关闭音频流
                    if (convertedAudioStream != null) {
                        try {
                            convertedAudioStream.close();
                        } catch (IOException e) {
                            log.error("关闭转换后的音频流时发生错误", e);
                        }
                    }
                    
                    // 清理所有临时文件
                    if (wavFile != null) {
                        fileCleanupService.cleanupFile(wavFile.toPath());
                    }
                    if (tempFile != null) {
                        fileCleanupService.cleanupFile(tempFile.toPath());
                    }
                }
            } catch (Exception e) {
                log.error("处理音频文件时发生错误", e);
                throw e;
            }
        }
    }

    // 判断是否需要添加空格的辅助方法
    private boolean shouldAddSpace(String lastText, String currentText) {
        // 如果上一段文本以标点符号结尾，不添加空格
        if (lastText.matches(".*[，。！？、]$")) {
            return false;
        }
        
        // 如果当前文本以标点符号开始，不添加空格
        if (currentText.matches("^[，。！？、].*")) {
            return false;
        }
        
        // 如果是不同的句子或短语，添加空格
        return true;
    }
} 