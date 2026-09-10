package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekAdvisoryClient {
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public DeepSeekAdvisoryClient(ObjectMapper mapper,
                                  @Value("${mission.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                                  @Value("${mission.ai.deepseek.api-key:}") String apiKey,
                                  @Value("${mission.ai.deepseek.model:deepseek-chat}") String model,
                                  @Value("${mission.ai.deepseek.timeout-seconds:20}") int timeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(3, timeoutSeconds))).build();
    }

    public boolean configured() { return !apiKey.isBlank(); }
    public String provider() { return "DEEPSEEK"; }
    public String model() { return model; }

    public Map<String, Object> explain(Map<String, Object> facts) throws Exception {
        if (!configured()) throw new IllegalStateException("DeepSeek 未配置");
        String system = "你是城市空地协同物流任务副驾驶。输入中的 options、eligible、rank 和 impact 均由服务端规则计算。"
                + "你只能比较和解释这些给定事实，不得选择最终执行方案，不得修改或重新计算任何数值、资格、排名、坐标、设备状态或 ACK。"
                + "仅返回 JSON：summary 字符串，rationale/risks/observations 字符串数组。明确指出需要人工批准且设备命令当前不可执行。";
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                "stream", false,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", mapper.writeValueAsString(facts))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("DeepSeek HTTP " + response.statusCode());
        Map<String, Object> payload = mapper.readValue(response.body(), new TypeReference<>() {});
        Object choicesValue = payload.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty() || !(choices.get(0) instanceof Map<?, ?> choice))
            throw new IllegalStateException("DeepSeek 响应缺少 choices");
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message) || !(message.get("content") instanceof String content))
            throw new IllegalStateException("DeepSeek 响应缺少 message.content");
        Map<String, Object> generated = mapper.readValue(content, new TypeReference<>() {});
        return sanitize(generated);
    }

    private static Map<String, Object> sanitize(Map<String, Object> generated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", boundedText(generated.get("summary"), 500));
        for (String field : List.of("rationale", "risks", "observations")) result.put(field, boundedList(generated.get(field)));
        return result;
    }

    private static String boundedText(Object value, int maxLength) {
        String text = String.valueOf(value == null ? "" : value).trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static List<String> boundedList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().limit(8).map(item -> boundedText(item, 240)).filter(item -> !item.isBlank()).toList();
    }
}
