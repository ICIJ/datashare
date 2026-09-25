package org.icij.datashare.tasks;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

public class InMemoryStatementRepository implements StatementRepository {
    final Map<String, Statement> stored = new LinkedHashMap<>();

    @Override
    public int save(String projectId, String runId, Stream<Statement> statements) {
        List<Statement> written = statements.filter(statement -> !stored.containsKey(statement.id())).toList();
        written.forEach(statement -> stored.put(statement.id(), statement));
        return written.size();
    }

    @Override
    public int deleteBySheet(String projectId, String documentId, String sheet) {
        String section = Statement.Provenance.sheetOrEmpty(sheet);
        List<String> doomed = stored.values().stream()
                                    .filter(statement -> statement.provenance().documentId().equals(documentId))
                                    .filter(statement -> statement.provenance().sheet().equals(section))
                                    .map(Statement::id).collect(toList());
        doomed.forEach(stored::remove);
        return doomed.size();
    }

    @Override
    public Replaced replace(String projectId, String runId, String documentId, String sheet,
                            Stream<Statement> statements) {
        int retracted = deleteBySheet(projectId, documentId, sheet);
        int written = 0;
        for (Statement statement : statements.toList()) {
            stored.put(statement.id(), statement);
            written++;
        }
        return new Replaced(retracted, written);
    }

    @Override
    public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
        List<ModelEntity> entities = new ArrayList<>();
        stored.values().stream().collect(groupingBy(Statement::entityId))
              .forEach((id, group) -> entities.add(ModelEntity.from(group, Set.of("1"))));
        return consumer.apply(entities.stream());
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        return entities(projectId, all -> all.filter(entity -> entity.id().equals(entityId)).findFirst());
    }
}
