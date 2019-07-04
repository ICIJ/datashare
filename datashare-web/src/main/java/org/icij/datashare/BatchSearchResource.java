package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Prefix("/api/batch")
public class BatchSearchResource {
    private final BatchSearchRepository batchSearchRepository;

    @Inject
    public BatchSearchResource(final BatchSearchRepository batchSearchRepository) {
        this.batchSearchRepository = batchSearchRepository;
    }

    @Post("search")
    public Payload search(Context context) throws IOException {
        List<Part> parts = context.parts();
        if (parts.size() != 3 || !"name".equals(parts.get(0).name()) ||
                !"description".equals(parts.get(1).name()) || !"search".equals(parts.get(2).name())) {
            return Payload.badRequest();
        }
        Part namePart = parts.get(0);
        Part descPart = parts.get(1);
        Part csv = parts.get(2);
        return batchSearchRepository.save((User)context.currentUser(),
                new BatchSearch(namePart.content(), descPart.content(), Arrays.asList(csv.content().split("\r\n")))) ?
                Payload.ok() : Payload.badRequest();
    }
}
