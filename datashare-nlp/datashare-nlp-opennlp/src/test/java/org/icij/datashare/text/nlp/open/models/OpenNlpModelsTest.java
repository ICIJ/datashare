package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.util.model.ArtifactProvider;
import opennlp.tools.util.model.BaseModel;
import org.icij.datashare.io.RemoteFiles;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.NlpStage;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class OpenNlpModelsTest {
    private final RemoteFiles mockRemoteFiles = mock(RemoteFiles.class);

    @Test
    public void test_download_model() throws Exception {
        final OpenNlpModels model = new OpenNlpModels(NlpStage.TOKEN);

        model.get(Language.FRENCH, getClass().getClassLoader());
        verify(mockRemoteFiles).download("dist/models/opennlp/1-5/fr", new File(System.getProperty("user.dir")));
        reset(mockRemoteFiles);

        model.get(Language.FRENCH, getClass().getClassLoader());
        verify(mockRemoteFiles, never()).download(any(String.class), any(File.class));
    }

    class OpenNlpModels extends org.icij.datashare.text.nlp.open.models.OpenNlpModels {
        protected OpenNlpModels(NlpStage stage) {super(stage);}
        @Override
        protected ArtifactProvider createModel(InputStream io) {return mock(BaseModel.class);}
        @Override
        String getModelPath(Language languate) { return "unused";}
        @Override
        protected boolean isDownloaded(Language language, ClassLoader loader) {return false;}
        @Override
        protected RemoteFiles getRemoteFiles() { return mockRemoteFiles;}
    }
}