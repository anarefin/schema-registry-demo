package com.example.consumer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Runtime validation is JSON Schema ({@code SchemaAwareMessageConverter}), not Bean Validation.
 * Contracts carry {@code jakarta.validation-api} for schema generation only; without a BV provider
 * Spring WebMVC would log {@code OptionalValidatorFactoryBean} INFO on startup.
 */
@Configuration
public class NoBeanValidationWebMvcConfiguration implements WebMvcConfigurer {

    private static final Validator NO_OP = new Validator() {
        @Override
        public boolean supports(Class<?> clazz) {
            return false;
        }

        @Override
        public void validate(Object target, Errors errors) {
            // no-op
        }
    };

    @Override
    public Validator getValidator() {
        return NO_OP;
    }
}
