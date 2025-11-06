package org.icij.datashare.tasks;

import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;

public class DelApiKeyTaskTest {
    @Mock
    public ApiKeyRepository apiKeyRepository;

    @Test
    public void test_call() throws Exception {
        ArgumentCaptor<User> user = forClass(User.class);
        assertThat(new DelApiKeyTask(apiKeyRepository, User.local()).call()).isNotNull();
        verify(apiKeyRepository).delete(user.capture());
        assertThat(user.getValue()).isEqualTo(User.local());
    }

    @Before
    public void setUp() throws Exception {
        openMocks(this);
    }
}