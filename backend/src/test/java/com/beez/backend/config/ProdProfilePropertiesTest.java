package com.beez.backend.config;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.agenttoken.AgentTokenProperties;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

/**
 * prod 프로필의 필수값 규칙 (S15P31A108-27, docs/infra/env.md).
 *
 * <p>Spring 은 {@code ${X}} 자리에 값이 없으면 오류 없이 {@code "${X}"} 글자를 넣고, 이 글자는 {@code @NotBlank} 를 통과한다.
 * 그래서 prod 필수값은 {@code ${X:}} 로 적어 빈 값이 검증에 걸리게 한다. 환경변수가 하나도 없는 환경으로 확인한다.
 */
class ProdProfilePropertiesTest {

    /** 일부러 {@code ${X}} 로 둔 자리. 빠지면 DB 접속 오류에 user "${DB_USERNAME}" 이 찍혀 원인이 보인다 */
    private static final Set<String> LITERAL_WHEN_MISSING = Set.of("spring.datasource.username");

    @Test
    void prod_설정은_값이_없을_때_자리표시_글자를_남기지_않는다() throws IOException {
        MockEnvironment env = new MockEnvironment();
        EnumerablePropertySource<?> prod = load("application-prod.yml");
        env.getPropertySources().addFirst(prod);

        List<String> literal = new ArrayList<>();
        for (String name : prod.getPropertyNames()) {
            if (LITERAL_WHEN_MISSING.contains(name)) continue;
            try {
                env.resolveRequiredPlaceholders(String.valueOf(prod.getProperty(name)));
            } catch (IllegalArgumentException unresolved) {
                literal.add(name);
            }
        }
        assertThat(literal).as("기본값 없는 ${X} — ${X:} 로 적어야 빈 값이 검증에 걸린다").isEmpty();
    }

    @Test
    void prod_에서_필수값이_비면_바인딩이_속성_이름을_짚어_실패한다() throws IOException {
        Binder binder = Binder.get(prodEnvironment());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            SpringValidatorAdapter validator = new SpringValidatorAdapter(factory.getValidator());

            // 검증은 처음 걸린 하위 객체에서 멈추므로 하나씩 바인딩한다
            assertBindFails(binder, validator, "beez.cors", BeezProperties.Cors.class, "allowedOrigins");
            assertBindFails(binder, validator, "beez.ai", BeezProperties.Ai.class, "baseUrl", "internalToken");
            assertBindFails(binder, validator, "beez.invitation", BeezProperties.Invitation.class, "baseUrl");
            assertBindFails(binder, validator, "beez.agent", AgentTokenProperties.class, "mcpUrl");
        }
    }

    private static void assertBindFails(Binder binder, SpringValidatorAdapter validator, String prefix, Class<?> type,
                                        String... fields) {
        // ValidationBindHandler 는 바인딩 결과를 쌓아 두므로 바인딩마다 새로 만든다
        BindHandler handler = new ValidationBindHandler(validator);
        Throwable failure = catchThrowable(() -> binder.bind(prefix, Bindable.of(type), handler));
        assertThat(failure).as(prefix + " 가 빈 값으로 통과했다").isNotNull();
        // 문구 형식이 아니라 검증 오류의 필드 이름으로 본다 (Spring 버전이 바뀌어도 규칙만 검사)
        assertThat(NestedExceptionUtils.getRootCause(failure)).isInstanceOf(BindValidationException.class);
        List<String> rejected = ((BindValidationException) NestedExceptionUtils.getRootCause(failure))
                .getValidationErrors().getAllErrors().stream()
                .map(error -> error instanceof FieldError field ? field.getField() : error.getObjectName())
                .toList();
        assertThat(rejected).as(prefix).contains(fields);
    }

    /** 기동 때와 같은 우선순위: application-prod.yml 이 application.yml 을 덮는다. */
    private static MockEnvironment prodEnvironment() throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addLast(load("application-prod.yml"));
        env.getPropertySources().addLast(load("application.yml"));
        return env;
    }

    private static EnumerablePropertySource<?> load(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }
}
