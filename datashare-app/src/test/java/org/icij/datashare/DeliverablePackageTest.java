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
    public void test_compare_deliverables() throws IOException {
        ExtensionService extensionService = new ExtensionService(deliverableDir.getRoot().toPath());
        DeliverablePackage deliverablePackageA = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-A.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        DeliverablePackage deliverablePackageB = new DeliverablePackage(extensionService.newDeliverable(deliverableDir.newFile( "my-extension-B.jar").toURI().toURL()),deliverableDir.getRoot().toPath(),extensionService.deliverableRegistry);
        assertThat(deliverablePackageA.compareTo(deliverablePackageB)).isLessThan(0);
    }

}