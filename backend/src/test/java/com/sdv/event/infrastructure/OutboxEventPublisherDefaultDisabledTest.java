package com.sdv.event.infrastructure;

import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M09A 초점 검증(Section 7, 카테고리 9) - {@code sdv.sync.outbox-publisher.enabled}를
 * 전혀 설정하지 않은(모든 Profile의 기본값 그대로인) Application Context가:
 * (1) {@link OutboxEventPublisher} Bean 자체를 등록하지 않고, (2) 실제 Kafka Broker
 * 없이도 정상 기동한다(이 Test 자체가 Broker를 전혀 띄우지 않는다 - 그런데도 통과한다
 * 는 사실이 곧 증거다)는 것을 증명한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class OutboxEventPublisherDefaultDisabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void publisherBeanIsNotRegisteredAndNoLiveBrokerIsRequiredToStart() {
        assertThat(applicationContext.getBeanNamesForType(OutboxEventPublisher.class))
                .as("sdv.sync.outbox-publisher.enabled defaults to false in every profile - the bean must not exist")
                .isEmpty();
    }
}
