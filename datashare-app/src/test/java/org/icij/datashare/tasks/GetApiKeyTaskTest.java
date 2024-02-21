package org.icij.datashare.tasks;

import org.icij.datashare.db.tables.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.*;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class GetApiKeyTaskTest {
    @Mock
    public ApiKeyRepository apiKeyRepository;

    @Test
    public void test_call() throws Exception {
        ArgumentCaptor<User> user = forClass(User.class);
        when(apiKeyRepository.get(User.local())).thenReturn(new DatashareApiKey(User.local()));
        assertThat(new GetApiKeyTask(apiKeyRepository, User.local()).call()).isNotNull();
        verify(apiKeyRepository).get(user.capture());
        assertThat(user.getValue()).isEqualTo(User.local());
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }

}