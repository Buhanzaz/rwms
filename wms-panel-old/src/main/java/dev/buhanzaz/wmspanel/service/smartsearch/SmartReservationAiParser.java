package dev.buhanzaz.wmspanel.service.smartsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

@Service
public class SmartReservationAiParser {

    private static final Logger log = LoggerFactory.getLogger(SmartReservationAiParser.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private static final String PROMPT = """
            Извлеки структурированные критерии поиска резерва из русского запроса.
            Верни только JSON без пояснений и без markdown.
            Используй только коды и значения из справочного контекста.
            Не выдумывай новые значения.
            Если значение нельзя сопоставить с контекстом, оставь поле null или пустой список.
            Для склада можно использовать code, city или name из контекста, но в ответе предпочтителен code.

            Справочный контекст:
            {context}

            Запрос:
            {text}
            """;

    private final SmartReservationAiProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public SmartReservationAiParser(SmartReservationAiProperties properties, ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return properties != null && properties.isConfigured();
    }

    public Optional<SmartReservationAiDraft> parse(String text, String contextJson) {
        if (text == null || text.isBlank() || !isConfigured()) {
            return Optional.empty();
        }

        try {
            ChatModel chatModel = chatModelProvider.getIfAvailable();
            if (chatModel == null) {
                return Optional.empty();
            }
            ChatResponse response = chatModel.call(new Prompt(
                    PROMPT.replace("{context}", contextJson == null ? "" : contextJson)
                            .replace("{text}", text)));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return Optional.empty();
            }
            String content = response.getResult().getOutput().getText();
            String json = extractJson(content);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, SmartReservationAiDraft.class));
        } catch (Exception ex) {
            log.warn("Smart reservation AI extraction failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
