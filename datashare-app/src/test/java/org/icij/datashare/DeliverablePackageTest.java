package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;

import static org.fest.assertions.Assertions.assertThat;

public class DeliverablePackageTest {
    @Rule public TemporaryFolder deliverableDir = new TemporaryFolder();
    public DeliverableRegistry<Extension> deliverableRegistry = new DeliverableRegistry<>(new ArrayList<>());

    @Test(expected = IllegalStateException.class)
    public void test_null_installed_deliverable(){
        new DeliverablePackage(null,deliverableDir.getRoot().toPath(), null);
    }

    @Test
    public void test_compare_deliverables_to_equals_without_registry() throws IOException {
        DeliverablePackage d1 = new DeliverablePackage(new Extension(deliverableDir.newFile("my-extension-1.0.0.jar").toURI().toURL()),deliverableDir.getRoot().toPath(), null);
        DeliverablePackage d2 = new DeliverablePackage(new Extension(deliverableDir.getRoot().toPath().resolve("my-extension-1.0.0.jar").toUri().toURL()),deliverableDir.getRoot().toPath(), null);
        assertThat(d1.equals(d2)).isTrue();
        assertThat(d1.compareTo(d2)).isEqualTo(0);

    }

    @Test
    public void test_compare_deliverables_to_equals_with_registry() throws IOException {
        DeliverableRegistry<Extension> deliverableRegistry = new DeliverableRegistry<>(Collections.singletonList(new Extension("my-extension",null,"1.0.0"
                ,null, deliverableDir.getRoot().toPath().resolve("my-extension-1.0.0.jar").toUri().toURL(),deliverableDir.getRoot().toURI().toURL(), Deliverable.Type.NLP)));
        DeliverablePackage d1 = new DeliverablePackage(deliverableRegistry.get("my-extension"),deliverableDir.getRoot().toPath(), deliverableRegistry.get("my-extension"));
        DeliverablePackage d2 = new DeliverablePackage(new Extension(deliverableDir.newFile("my-extension-1.0.0.jar").toURI().toURL()),deliverableDir.getRoot().toPath(), null);
        assertThat(d1.equals(d2)).isTrue();
        assertThat(d1.compareTo(d2)).isEqualTo(0);
    }

    @Test
    public void test_compare_deliverables_to_equals_with_null_installed_version() throws IOException {
        Extension extensionFromRegistry = new Extension("my-extension", null, "1.0.0", null
                , deliverableDir.getRoot().toPath().resolve("my-extension-1.0.0.jar").toUri().toURL(), deliverableDir.getRoot().toURI().toURL(), Deliverable.Type.NLP);
        Extension installedExtension = new Extension(new URL("file:///my-extension-1.0.1.jar"));
        DeliverablePackage d1 = new DeliverablePackage(null,deliverableDir.getRoot().toPath(),extensionFromRegistry);
        DeliverablePackage d2 = new DeliverablePackage(installedExtension,deliverableDir.getRoot().toPath(),null);
        assertThat(d1.equals(d2)).isTrue();
        assertThat(d1.compareTo(d2)).isEqualTo(0);
    }

    @Test
    public void test_is_installed_deliverable() throws IOException {
        URL extensionUrl = deliverableDir.newFile("my-extension.jar").toURI().toURL();
        Extension installedDeliverable = new Extension(extensionUrl);
        DeliverablePackage d = new DeliverablePackage(installedDeliverable, deliverableDir.getRoot().toPath(),null);
        assertThat(d.isInstalled()).isTrue();
    }
}