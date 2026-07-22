package com.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class DeepSeekService {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public DeepSeekService(@Value("${app.deepseek.api-key}") String apiKey,
                           @Value("${app.deepseek.base-url}") String baseUrl,
                           @Value("${app.deepseek.model}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String chat(String systemPrompt, String userPrompt) throws IOException {
        return chat(systemPrompt, userPrompt, 8192);
    }

    public String chat(String systemPrompt, String userPrompt, int maxTokens) throws IOException {
        var body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.7,
                "max_tokens", maxTokens
        );

        var req = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json.writeValueAsString(body), MediaType.parse("application/json")))
                .build();

        try (var resp = client.newCall(req).execute()) {
            String bodyStr = resp.body().string();
            var tree = json.readTree(bodyStr);
            if (tree.get("choices") == null) {
                throw new IOException("DeepSeek 返回异常: " + bodyStr.substring(0, Math.min(500, bodyStr.length())));
            }
            return tree.get("choices").get(0).get("message").get("content").asText();
        }
    }

    /**
     * 流式调用 DeepSeek，逐 token 回调
     * @param onToken 每收到一个文本片段就回调
     */
    public void chatStream(String systemPrompt, String userPrompt, Consumer<String> onToken) throws IOException {
        var body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.7,
                "max_tokens", 8192,
                "stream", true
        );

        var req = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json.writeValueAsString(body), MediaType.parse("application/json")))
                .build();

        try (var resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("DeepSeek 返回空响应");
            try (var reader = new BufferedReader(new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        var tree = json.readTree(data);
                        var delta = tree.path("choices").path(0).path("delta").path("content");
                        if (!delta.isMissingNode() && !delta.asText().isEmpty()) {
                            onToken.accept(delta.asText());
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
    }
}
