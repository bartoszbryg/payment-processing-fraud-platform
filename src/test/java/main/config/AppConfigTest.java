package main.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void restTemplate_beanIsCreated() {
        AppConfig config = new AppConfig();
        RestTemplateBuilder builder = new RestTemplateBuilder();

        RestTemplate restTemplate = config.restTemplate(builder);

        assertNotNull(restTemplate);
    }

    @Test
    void restTemplate_isRestTemplateInstance() {
        AppConfig config = new AppConfig();

        RestTemplate restTemplate = config.restTemplate(new RestTemplateBuilder());

        assertInstanceOf(RestTemplate.class, restTemplate);
    }
}