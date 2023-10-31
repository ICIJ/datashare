package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

import static java.util.Optional.ofNullable;

public class DeliverablePackage implements Comparable<DeliverablePackage>{
    @JsonIgnore
    private final Path deliverablesDir;
    @JsonIgnore
    private final Deliverable installedDeliverable;
    private final Deliverable deliverableFromRegistry;

    public DeliverablePackage(Deliverable installedDeliverable, Path deliverableDir, Deliverable deliverableFromRegistry) {
        if (installedDeliverable == null && deliverableFromRegistry == null) {
            throw new IllegalStateException("cannot create deliverable package with both null deliverables");
        }
        this.deliverablesDir = deliverableDir;
        this.installedDeliverable = installedDeliverable;
        this.deliverableFromRegistry = deliverableFromRegistry;
    }

    public boolean isInstalled() {
        return installedDeliverable != null && deliverablesDir.resolve(installedDeliverable.getBasePath()).toFile().exists();
    }

    public Deliverable reference() {return ofNullable(installedDeliverable).orElse(deliverableFromRegistry);}

    public Deliverable getInstalledDeliverable() {return installedDeliverable;}

    public void displayInformation() {
        Deliverable deliverable = ofNullable(deliverableFromRegistry).orElse(installedDeliverable);
        System.out.println(deliverable.getClass().getSimpleName() + " " + deliverable.getId() + (isInstalled() ? " **INSTALLED** " : ""));
        System.out.println("\t" + deliverable.getName());
        System.out.println("\t" + deliverable.getDescription());
        if (isInstalled()) {
            System.out.println("\t" + installedDeliverable.getVersion());
        }
        if (deliverableFromRegistry != null) {
            System.out.println("\t" + deliverableFromRegistry.getVersion() + " Candidate Version");
            System.out.println("\t" + deliverableFromRegistry.getHomepage());
        }
        System.out.println("\t" + deliverable.getUrl());
        System.out.println("\t" + deliverable.getType());
    }

    public String getId() {return reference().getId();}
    public String getName() {return reference().getName();}
    public String getDescription() {return reference().getDescription();}
    public Deliverable.Type getType() {return reference().getType();}
    public String getVersion() {return reference().getVersion();}

    @Override
    public String toString() {
        return  "Package id="+ reference().getId() +
                " version=" + reference().getVersion();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeliverablePackage that = (DeliverablePackage) o;
        return compareTo(that) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(installedDeliverable);
    }

    @Override
    public int compareTo(@NotNull DeliverablePackage deliverablePackage) {
        Deliverable myDeliverable = ofNullable(installedDeliverable).orElse(deliverableFromRegistry);
        Deliverable otherDeliverable = ofNullable(deliverablePackage.installedDeliverable).orElse(deliverablePackage.deliverableFromRegistry);
        return myDeliverable.getId().compareTo(otherDeliverable.getId());
    }
}
