package org.icij.datashare.session;

import org.casbin.jcasbin.model.Model;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserProjectPolicyAdapterTest {

    @Mock
    private UserPolicyRepository repository;

    private UserPolicyAdapter adapter;

    @Before
    public void setUp() {
        openMocks(this);
        adapter = new UserPolicyAdapter(repository);
    }


    @Test
    public void test_adapter_load_policy() throws URISyntaxException {
        UserPolicy policy1 = new UserPolicy("user1", "project1", new Role[] {Role.READER});
        UserPolicy policy2 = new UserPolicy("user2", "project2", new Role[] {Role.WRITER,Role.ADMIN});
        when(repository.getAllPolicies()).thenReturn(Stream.of(policy1, policy2));

        Model model = new Model();
        String filePath = "casbin/model.conf";
        Path path = Paths.get(ClassLoader.getSystemResource(filePath).toURI());

        model.loadModel(path.toString());
        adapter.loadPolicy(model);

        List<List<String>> loadedPolicies = model.model.get("p").get("p").policy;
        assertTrue(loadedPolicies.contains(List.of("user1", "project1", "READER")));
        assertFalse(loadedPolicies.contains(List.of("user1", "project2", "READER")));
        assertTrue(loadedPolicies.contains(List.of("user2", "project2", "WRITER")));
        assertTrue(loadedPolicies.contains(List.of("user2", "project2", "ADMIN")));
    }
}
