package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;

public class DeliverablePackageTest {
    @Rule
    public TemporaryFolder deliverableDir = new TemporaryFolder();

    @Test
    public void test_compare_deliverables_with_id() throws IOException {
        ExtensionService extensionService = new ExtensionService(deliverableDir.getRoot().toPath());
        DeliverablePackage deliverablePackageA = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-A.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        DeliverablePackage deliverablePackageB = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-B.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        assertThat(deliverablePackageA.compareTo(deliverablePackageB)).isLessThan(0);
    }

    @Test
    public void test_compare_deliverables_with_same_id_different_version() throws IOException {
        ExtensionService extensionService = new ExtensionService(deliverableDir.getRoot().toPath());
        DeliverablePackage deliverablePackageA = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-1.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        DeliverablePackage deliverablePackageB = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-2.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        assertThat(deliverablePackageA.compareTo(deliverablePackageB)).isLessThan(0);
    }

    @Test
    public void test_list_installed_deliverable() throws IOException {
        ExtensionService extensionService = new ExtensionService(deliverableDir.getRoot().toPath());
        DeliverablePackage deliverablePackageA = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        assertThat(deliverablePackageA.isInstalled()).isTrue();
    }

    @Test
    public void test_get_installed_version() throws IOException {
        ExtensionService extensionService = new ExtensionService(deliverableDir.getRoot().toPath());
        DeliverablePackage deliverablePackageA = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-1.0.0.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        assertThat(deliverablePackageA.getInstalledVersion()).isEqualTo("1.0.0");
    }

}