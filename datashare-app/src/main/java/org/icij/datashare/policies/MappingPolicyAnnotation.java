package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingService;
import org.icij.datashare.tabular.ForbiddenExtractionMapping;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.icij.datashare.utils.PayloadFormatter;
import java.util.Optional;
import java.util.function.Function;
import static org.icij.datashare.web.errors.BadRequestException.badRequestIfInvalid;

/** Translates the decision {@link ExtractionMappingService#get} makes into a status code. */
public class MappingPolicyAnnotation implements ApplyAroundAnnotation<MappingPolicy> {
    private final ExtractionMappingService mappings;

    @Inject
    public MappingPolicyAnnotation(ExtractionMappingService mappings) {
        this.mappings = mappings;
    }

    @Override
    public Payload apply(MappingPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());
        String projectId = badRequestIfInvalid(() -> Authorizer.requireValue(context.pathParam("project"), false));
        Optional<ExtractionMapping> mapping;
        try {
            mapping = mappings.get(projectId, context.pathParam("mappingId"), user);
        } catch (ForbiddenExtractionMapping e) {
            return Payload.forbidden();
        } catch (UnreadableExtractionMapping e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.CONFLICT);
        }
        return mapping.isPresent() ? payloadSupplier.apply(context) : Payload.notFound();
    }
}
