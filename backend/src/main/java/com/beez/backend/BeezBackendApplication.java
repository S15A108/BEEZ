package com.beez.backend;

import java.util.function.IntConsumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan   // beez.* → BeezProperties 바인딩
public class BeezBackendApplication {

    public static void main(String[] args) {
        start(() -> SpringApplication.run(BeezBackendApplication.class, args), System::exit);
    }

    /**
     * 기동이 실패하면 JVM 을 끝낸다(exit 1) — S15P31A108-298.
     * 실패한 컨텍스트가 닫혀도 비데몬 스레드가 남으면 main 이 끝나도 JVM 이 살아 있어 컨테이너가 running 으로 남고
     * 재시작 정책(restart: unless-stopped)이 돌지 않는다. 실측 원인은 MCP SDK 2.1.0 의 세션 정리 스케줄러
     * (Schedulers.newSingle, 비데몬)로, closeGracefully 가 구독만 끊고 스케줄러는 닫지 않는다. 원인이 무엇이든 막도록 여기서 끝낸다.
     * 실패 내용은 SpringApplication 이 이미 로그로 남긴 뒤다.
     */
    static void start(Runnable run, IntConsumer exit) {
        try {
            run.run();
        } catch (RuntimeException startupFailure) {
            exit.accept(1);
        }
    }
}
