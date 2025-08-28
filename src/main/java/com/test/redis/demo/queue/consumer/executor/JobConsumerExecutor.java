package com.test.redis.demo.queue.consumer.executor;

import com.test.redis.demo.queue.consumer.JobConsumer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobConsumerExecutor {

    private final TaskExecutor taskExecutor;

    // Spring이 JobConsumer 인터페이스를 구현하는 모든 Bean들을 자동으로 찾아서 List로 주입
    private final List<JobConsumer> consumers;

    @PostConstruct
    public void startAllConsumers() {
        log.info("{}개의 job consumer를 실행합니다.", consumers.size());

        // 주입받은 모든 Consumer들을 하나씩 순회하면서, 각각의 consume() 메소드를 별도의 스레드에서 실행
        for (JobConsumer consumer : consumers) {
            taskExecutor.execute(consumer::consume);
        }
    }
}