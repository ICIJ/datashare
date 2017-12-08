package org.icij.datashare.text.nlp.open.models;

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
        final OpenNlpModel model = new OpenNlpModel(NlpStage.TOKEN);

        model.get(Language.FRENCH, getClass().getClassLoader());
        verify(mockRemoteFiles).download("/dist/models/opennlp/fr", new File("models/inexistant"));
        reset(mockRemoteFiles);

        model.get(Language.FRENCH, getClass().getClassLoader());
        verify(mockRemoteFiles, never()).download(any(String.class), any(File.class));
    }

    class OpenNlpModel extends OpenNlpAbstractModel {
        BaseModel model = null;
        public OpenNlpModel(NlpStage stage) {super(stage);}
        @Override
        BaseModel getModel(Language language) { return model;}
        @Override
        void putModel(Language language, InputStream content) {model = mock(BaseModel.class);}
        @Override
        String getModelPath(Language language) {
            return "models/inexistant";
        }
        @Override
        RemoteFiles getRemoteFiles() { return mockRemoteFiles;}
    }
}