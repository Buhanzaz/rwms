package dev.buhanzaz.rwms.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "rwms.auth.issuer=https://edge.example.test/auth")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthGatewayPrefixIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void discoveryUsesExactlyOneAuthPrefixAndForwardedLoginStaysUnderPrefix() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://edge.example.test/auth"))
                .andExpect(jsonPath("$.authorization_endpoint")
                        .value("https://edge.example.test/auth/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint")
                        .value("https://edge.example.test/auth/oauth2/token"));

        mvc.perform(get("/oauth2/authorize")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "edge.example.test")
                        .header("X-Forwarded-Prefix", "/auth")
                        .accept(MediaType.TEXT_HTML)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-panel")
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "gateway-prefix-state")
                        .queryParam("nonce", "gateway-prefix-nonce")
                        .queryParam("redirect_uri", "http://localhost:8080/auth/callback")
                        .queryParam("code_challenge", "5JpQbJlRyOBY47l0mJC0RGkxXcsBqmCOlYz7TcneQKc")
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://edge.example.test/auth/login"));

        mvc.perform(get("/login")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "edge.example.test")
                        .header("X-Forwarded-Prefix", "/auth"))
                .andExpect(status().isOk());

        mvc.perform(post("/logout")
                        .with(csrf())
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "edge.example.test")
                        .header("X-Forwarded-Prefix", "/auth"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://edge.example.test/auth/login?logout"));

        String index = new ClassPathResource("static/index.html").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(index).contains("src=\"./assets/", "href=\"./assets/");
        String scriptPath = index.substring(index.indexOf("src=\"") + 5, index.indexOf("\"", index.indexOf("src=\"") + 5));
        String script = new ClassPathResource("static/" + scriptPath.substring(2))
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(script)
                .contains("api/auth/csrf")
                .contains("action:\"login\"")
                .contains("src:\"wms-login-cover.png\"");
    }
}
