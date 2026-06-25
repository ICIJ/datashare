package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.nio.file.Path;

/** Everything produce() needs: the node, its content-addressed dir, and a way to open source bytes. */
public record ArtifactContext(Project project, Document document, Path nodeDir, SourceExtractor sources) {}
