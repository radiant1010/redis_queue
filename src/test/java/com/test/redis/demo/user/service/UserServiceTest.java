package com.test.redis.demo.user.service;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.queue.payload.JobPayload;
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
    private StagingManageService<UserDTO> mockUserStagingService;

    @Test
    @DisplayName("submitJob 호출 시, StagingManageService에게 작업을 올바르게 위임해야 한다")
    public void successTestUserAddProcess() {
        // given : UserDTO 리스트 Stub 생성
        List<UserDTO> mockUsers = List.of(
                new UserDTO("test1@test.com", "pw1"),
                new UserDTO("tes2t@test.com", "pw2")
        );

        String mockJobId = "fake-uuid-for-test-123";
        when(mockUserStagingService.processAndEnqueue(mockUsers)).thenReturn(mockJobId);

        // when : submitJob 메서드 호출
        String resultJobId = userService.submitJob(mockUsers);

        // then
        verify(mockUserStagingService, times(1)).processAndEnqueue(mockUsers);
        assertThat(resultJobId).isEqualTo(mockJobId);
    }
}