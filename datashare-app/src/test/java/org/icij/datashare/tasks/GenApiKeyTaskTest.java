package org.icij.datashare.tasks;

import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class GenApiKeyTaskTest {
    @Mock public ApiKeyRepository apiKeyRepository;

    @Test
    public void test_call() throws Exception {
        ArgumentCaptor<DatashareApiKey> apiKey = forClass(DatashareApiKey.class);
        assertThat(new GenApiKeyTask(apiKeyRepository, User.local()).call()).isNotNull();
        verify(apiKeyRepository).save(apiKey.capture());
        assertThat(apiKey.getValue().getUser()).isEqualTo(User.local());
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }
}
