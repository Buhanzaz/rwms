package dev.buhanzaz.wmspanel.service.smartsearch;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmartReservationAiConfiguration {

    @Bean(name = "smartReservationChatModel")
    public ChatModel smartReservationChatModel(SmartReservationAiProperties properties) {
        if (properties == null || !properties.isConfigured()) {
            return null;
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(0.0)
                        .build())
                .build();
    }
}
