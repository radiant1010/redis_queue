package com.test.redis.demo.queue.consumer.executor;

import com.test.redis.demo.queue.consumer.JobConsumer;
import com.test.redis.demo.queue.consumer.QueueJobConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Redis Queue를 실행 시키며 SmartLifecycle을 주입받아 종료 라이프 사이클을 커스텀하게 제어
 * @Description : 기존의 !Thread.currentThread().isInterrupted()만으로는 쓰레드가 정상적으로 종료가 되지 않음
 * ex: 스프링 어플리케이션 종료 -> 쓰레드 종료 안됨 -> LettuceConnectionFactory 지속적으로 호출 -> 스프링 어플리케이션 종료가 안되고
 * while(!Thread.currentThread().isInterrupted()) 조건을 종료 시키지 못하는 문제 발생 -> 무한 오류 로그 출력
 * 따라서 커스텀 하게 Lifecycle를 제어하여 Redis Queue 연결을 제어한다.
 *
 * @Link : https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle
 * https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html
 * */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobConsumerExecutor implements SmartLifecycle {

    @Qualifier("jobConsumerTaskExecutor")
    private final TaskExecutor taskExecutor;
    private final List<JobConsumer> consumers;

    /* Lifecycle 관리를 위한 의존성 주입 */
    private final List<Future<?>> runningTasks = new ArrayList<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void start() {
        log.warn("--- [Lifecycle START] JobConsumerExecutor 시작 ---");
        log.info("{}개의 job consumer를 실행합니다.", consumers.size());
        for (JobConsumer consumer : consumers) {
            if (taskExecutor instanceof ThreadPoolTaskExecutor executor) {
                Future<?> task = executor.submit(consumer::consume);
                runningTasks.add(task);
            }
        }
        isRunning.set(true);
    }

    @Override
    public void stop(Runnable callback) {
        log.warn("--- [Lifecycle STOP] JobConsumerExecutor 종료 절차 시작 ---");
        isRunning.set(false);
        
        // 작업 종료 요청 이후 신규 작업은 추가 하지 않도록 요청
        log.warn("[JobConsumerExecutor 종료 1/2] Consumer들에게 중지 명령 전달, 진행 중인 작업은 계속 처리 중...");
        consumers.forEach(consumer -> {
            if (consumer instanceof QueueJobConsumer userJobConsumer) {
                userJobConsumer.stop();
            }
        });

        // 스레드 풀에게 추가 작업 요청을 받지 말고, 현재 작업이 끝나면 쓰레드를 종료 해달라고 요청
        log.warn("[JobConsumerExecutor 종료 2/2] 스레드 풀에 종료 요청...");
        if (taskExecutor instanceof ThreadPoolTaskExecutor executor) {
            executor.shutdown();
        }

        // 종료 콜백을 실행하여 Spring에게 JobConsumer의 종료 절차를 알립니다. Spring의 종료 프로세스가 대기함
        callback.run();
        log.warn("--- [Lifecycle STOP] JobConsumerExecutor 종료 절차 완료 ---");
    }

    /* 이하 SmartLifecycle 제어를 위한 메서드 Override */
    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return isRunning.get();
    }
    
    // 최우선 순위로 설정
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
