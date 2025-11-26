package org.icij.datashare.utils;

import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.Mode;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ModeVerifierTest {
    @Mock private PropertiesProvider propertiesProvider;
    private ModeVerifier modeVerifier;

    @Before
    public void setUp() {
        initMocks(this);
        modeVerifier = new ModeVerifier(propertiesProvider);
    }

    @Test(expected = ForbiddenException.class)
    public void test_check_allowed_mode_should_throw_forbidden_exception() throws ForbiddenException {
        when(propertiesProvider.get("mode")).thenReturn(Optional.of("SERVER"));
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
    }

    @Test
    public void test_check_allowed_mode_should_not_throw_exception() throws ForbiddenException {
        when(propertiesProvider.get("mode")).thenReturn(Optional.of(Mode.LOCAL.name()));
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
    }
}
