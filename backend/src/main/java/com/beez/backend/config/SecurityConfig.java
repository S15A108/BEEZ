package com.beez.backend.config;

import com.beez.backend.auth.SecurityErrorWriter;
import com.beez.backend.auth.agent.AgentTokenAuthenticationFilter;
import com.beez.backend.auth.agent.AgentTokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * 인증 필터 체인 3개 (-172 구현 메모).
 * <ol>
 *   <li>/mcp — 프로젝트 에이전트 토큰. 세션을 만들지 않는다.</li>
 *   <li>/api/** — 사람 세션 쿠키. PUBLIC 경로만 열고 나머지는 로그인 필요.</li>
 *   <li>그 밖 — 헬스체크·OpenAPI 만 열고 나머지는 막는다.</li>
 * </ol>
 * 여기서는 "로그인했는가"까지만 본다. 프로젝트 멤버·역할은 서비스 첫 줄의 ProjectAccessChecker 가 본다.
 */
@Configuration
public class SecurityConfig {

    /** 00-common 2절 PUBLIC. 로그아웃은 만료된 세션의 재요청도 204 여야 해서(ACC-A03) 열어 둔다. */
    static final String[] PUBLIC_API = {
            "/api/ping",
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/password/**",
            "/api/invitations/preview",
    };

    /** 배포 확인(-126)·문서. 운영에서 OpenAPI 는 SWAGGER_ENABLED 로 따로 끈다. */
    static final String[] PUBLIC_OPS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/error",
    };

    @Bean
    @Order(1)
    SecurityFilterChain mcpChain(HttpSecurity http, AgentTokenVerifier verifier, SecurityErrorWriter errors)
            throws Exception {
        http.securityMatcher("/mcp", "/mcp/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(new AgentTokenAuthenticationFilter(verifier, errors), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, SecurityErrorWriter errors,
                                 SecurityContextRepository contextRepository,
                                 @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookie)
            throws Exception {
        http.securityMatcher("/api/**")
                .cors(Customizer.withDefaults())   // WebConfig 의 CORS 설정을 preflight 에도 적용
                // Q04 제안: SameSite=Lax 세션 쿠키 + JSON API + CORS 오리진 제한으로 막는다. CSRF 토큰을 쓰기로 하면 여기서 켠다.
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)   // 비로그인 401 에 세션을 만들지 않는다
                .securityContext(c -> c.securityContextRepository(contextRepository))
                .logout(l -> l
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies(sessionCookie))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC_API).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http, SecurityErrorWriter errors) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC_OPS).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /** 로그인 시 SecurityContext 를 HttpSession 에 저장하고, 이후 요청에서 읽는다. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** {bcrypt} 접두어로 저장해 나중에 알고리즘을 바꿔도 기존 해시를 그대로 검증할 수 있다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
