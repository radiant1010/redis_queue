package com.test.redis.demo.user.service;

import com.test.redis.demo.queue.dto.UserJobPayload;
import com.test.redis.demo.queue.key.JobType;
import com.test.redis.demo.queue.key.QueueType;
import com.test.redis.demo.queue.provider.QueueProvider;
import com.test.redis.demo.user.dto.UserDTO;
import com.test.redis.demo.util.SystemUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private QueueProvider mockQueueProvider;

    @Mock
    private SystemUtil mockSystemUtil;

    @Mock
    private RedisTemplate<String, Object> mockRedisTemplate;

    // opsForHash() 같은 체인 메소드를 모킹
    @Mock
    private HashOperations<String, String, Object> mockHashOperations;

    @BeforeEach
    void setUp() {
        // mockRedisTemplate.opsForHash() 호출 시 mockHashOperations 객체를 반환
        doReturn(mockHashOperations)
                .when(mockRedisTemplate)
                .opsForHash();
    }


    @Test
    @DisplayName("submitJob 호출 시, 생성된 jobId가 일관되게 사용되고, 의존 객체들이 올바르게 호출되어야 한다")
    public void successTestUserAddProcess() {
        // given : UserDTO 리스트 Stub 생성
        List<UserDTO> mockUsers = List.of(
                new UserDTO("test1@test.com", "pw1"),
                new UserDTO("tes2t@test.com", "pw2")
        );

        String mockJobId = "fake-uuid-for-test-123";
        when(mockSystemUtil.generatedUuid()).thenReturn(mockJobId);

        // when : submitJob 메서드 호출
        String jobId = userService.submitJob(mockUsers);

        // Redis 해시 저장이 호출되었는지 검증
        verify(mockHashOperations).put(QueueType.USER.getStagingKey(), mockJobId, mockUsers);

        // 큐 삽입 검증
        ArgumentCaptor<UserJobPayload> payloadCaptor = ArgumentCaptor.forClass(UserJobPayload.class);
        verify(mockQueueProvider, times(1)).enqueue(eq(QueueType.USER.getPendingKey()), payloadCaptor.capture());

        // payload 검증(payload는 작업을 수행할 staging queue의 jobId를 가지고 있는다)
        UserJobPayload capturedPayload = payloadCaptor.getValue();
        assertEquals(JobType.USER_ADD, capturedPayload.jobType());
        assertThat(capturedPayload.jobId()).isEqualTo(mockJobId);

        // 최종 결과는 jobId가 리턴되었는지 검증
        assertThat(jobId).isNotNull().isNotEmpty();
    }
}