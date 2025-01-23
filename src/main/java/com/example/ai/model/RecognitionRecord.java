package com.example.ai.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RecognitionRecord {
    private final String text;
    private final String timestamp;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public RecognitionRecord(String text) {
        this.text = text;
        this.timestamp = LocalDateTime.now().format(formatter);
    }

    public String getText() {
        return text;
    }

    public String getTimestamp() {
        return timestamp;
    }
} 