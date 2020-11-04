package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jetbrains.annotations.NotNull;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

import static java.util.Optional.ofNullable;

public class DeliverablePackage implements Comparable<DeliverablePackage>{
    @JsonIgnore
    private final Path deliverablesDir;
    @JsonIgnore
    private final Deliverable deliverableFromRegistry;
    @JsonIgnore
    private final Deliverable installedDeliverable;

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

    public Deliverable reference() {return ofNullable(deliverableFromRegistry).orElse(installedDeliverable);}

    public Deliverable getInstalledDeliverable() {return installedDeliverable;}

    public void displayInformation() {
        System.out.println(reference().getClass().getSimpleName() + " " + reference().getId() + (isInstalled() ? " **INSTALLED** : " +  getInstalledVersion() : ""));
        System.out.println("\t" + reference().getName());
        System.out.println("\t" + reference().getVersion());
        System.out.println("\t" + reference().getUrl());
        System.out.println("\t" + reference().getDescription());
        System.out.println("\t" + reference().getType());
    }

    public String getId() {return reference().getId();}
    public String getName() {return reference().getName();}
    public String getDescription() {return reference().getDescription();}
    public URL getUrl(){return reference().getUrl();}
    public Deliverable.Type getType() {return reference().getType();}
    public String getVersion() {return reference().getVersion();}
    public String getInstalledVersion() {return isInstalled() ? installedDeliverable.getVersion() : null;}

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
