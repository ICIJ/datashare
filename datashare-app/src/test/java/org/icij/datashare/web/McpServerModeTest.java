package org.icij.datashare.web;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.session.UsersInDb;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.String.format;

public class McpServerModeTest extends AbstractProdWebServerTest {
    private String apiKey;

    @Before
    public void setUp() throws Exception {
        Path datashareHome = Files.createTempDirectory("datashare-");
        String[] args = {
                "--mode=SERVER",
                format("--dataSourceUrl=jdbc:sqlite:file:%s/db.db", datashareHome),
                format("--pluginsDir=%s", datashareHome),
                format("--extensionsDir=%s", datashareHome)
        };
        CommonMode mode = CommonMode.create(new DatashareCli().parseArguments(args).properties);
        User alice = new User("alice");
        mode.get(UsersInDb.class).save(alice);
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        mode.get(ApiKeyRepository.class).save(new DatashareApiKey(secretKey, alice));
        apiKey = DatashareApiKey.getBase64Encoded(secretKey);
        configure(mode.createWebConfiguration());
        waitForDatashare();
    }

    @Test
    public void test_post_with_api_key_only_is_not_blocked_by_csrf() {
        post("/api/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .withHeader("Authorization", "Bearer " + apiKey)
                .should().respond(200)
                .contain("\"jsonrpc\":\"2.0\"")
                .contain("\"id\":1")
                .contain("\"protocolVersion\"")
                .contain("\"name\":\"datashare\"")
                .contain("\"tools\"");
    }

    @Test
    public void test_post_without_credentials_is_unauthorized() {
        post("/api/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .should().respond(401);
    }
}
