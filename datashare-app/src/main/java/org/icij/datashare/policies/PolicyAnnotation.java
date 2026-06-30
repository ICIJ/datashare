package org.icij.datashare.policies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;

import java.io.IOException;
import java.util.function.Function;

import static org.icij.datashare.policies.Authorizer.requireDomain;


public class PolicyAnnotation implements ApplyAroundAnnotation<Policy> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Authorizer authorizer;

    @Inject
    public PolicyAnnotation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Payload apply(Policy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());

        Domain domain;
        String projectId;
        if (annotation.role() == Role.INSTANCE_ADMIN) {
            domain = Domain.of("*");
            projectId = "*";
        } else {
            domain = requireDomain(annotation.domain(), true);
            try {
                projectId = annotation.role() == Role.DOMAIN_ADMIN ? "*" : resolveProjectId(context, annotation.idParam());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if (!authorizer.can(user.id, domain, projectId, annotation.role())) {
            return Payload.forbidden();
        }

        return payloadSupplier.apply(context);
    }

    private static String resolveProjectId(Context context, String idParam) throws IOException {
        String value = context.pathParam(idParam);
        if (value == null || value.isBlank()) {
            value = context.get(idParam);
        }
        if (value == null || value.isBlank()) {
            String jsonString = context.request().content();
            if (jsonString != null && !jsonString.isBlank()) {
                JsonNode jsonNode = OBJECT_MAPPER.readTree(jsonString);
                JsonNode field = jsonNode.get(idParam);
                if (field != null && !field.isNull()) {
                    value = field.asText();
                }
            }
        }
        return (value == null || value.isBlank()) ? "*" : value;
    }
}
