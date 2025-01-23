package com.example.ai.controller;

import com.example.ai.service.AudioService;
import com.example.ai.model.RecognitionRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.time.LocalDateTime;

@Controller
public class AudioController {
    private final AudioService audioService;

    @Autowired
    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("transcription", "等待识别结果...");
        return "index";
    }

    @GetMapping("/index")
    public String indexAlias(Model model) {
        return index(model);
    }

    @PostMapping("/processAudio")
    @ResponseBody
    public Map<String, Object> processAudio(@RequestParam("audio") MultipartFile audio) {
        try {
            String text = audioService.processAudioFile(audio);
            RecognitionRecord record = new RecognitionRecord(text);
            return Map.of(
                "success", true,
                "text", text,
                "timestamp", record.getTimestamp()
            );
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                "success", false,
                "error", "识别失败：" + e.getMessage()
            );
        }
    }
} 