package dev.buhanzaz.wmspanel.service.smartsearch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "spring.ai.openai.chat")
public class SmartReservationAiProperties {

    private String baseUrl = "https://api.mistral.ai/v1";
    private String apiKey;
    private final Options options = new Options();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return options.getModel();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Options getOptions() {
        return options;
    }

    public void setOptions(Options options) {
        if (options != null) {
            this.options.setModel(options.getModel());
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public static class Options {
        private String model = "mistral-small-latest";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
