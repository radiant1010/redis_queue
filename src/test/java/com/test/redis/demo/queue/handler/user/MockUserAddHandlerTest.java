package com.test.redis.demo.queue.handler.user;

import com.test.redis.demo.config.queue.StagingManageService;
import com.test.redis.demo.user.dto.UserDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockUserAddHandlerTest {
    @SuppressWarnings("unchecked")
    @Test
    void failedNotificationDoesNotChangeTerminalJobFailure() {
        StagingManageService<UserDTO> staging = mock(StagingManageService.class);
        UserJobClient client = mock(UserJobClient.class);
        FailureNotification notification = mock(FailureNotification.class);
        when(staging.getStagedData("job")).thenReturn(List.of(new UserDTO("fail@example.invalid", "Fixture")));
        doThrow(new IllegalStateException("exhausted")).when(client).execute(eq("job"), anyList());
        doThrow(new IllegalStateException("mail down")).when(notification).notifyFailure(anyString(), anyString());
        assertThat(new MockUserAddHandler(staging, client, notification).process("job")).isFalse();
        verify(notification, times(1)).notifyFailure(eq("job"), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shutdownInterruptionDoesNotProduceFailureMail() {
        StagingManageService<UserDTO> staging = mock(StagingManageService.class);
        UserJobClient client = mock(UserJobClient.class);
        FailureNotification notification = mock(FailureNotification.class);
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }).when(client).execute(eq("job"), any());
        try {
            assertThatThrownBy(() -> new MockUserAddHandler(staging, client, notification).process("job"))
                    .hasMessage("CLIENT_INTERRUPTED");
            verifyNoInteractions(notification);
        } finally {
            Thread.interrupted();
        }
    }
}
