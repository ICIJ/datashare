package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.ApiKeyRecord;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
// Keep these imports explicit otherwise the wildcard import of import org.jooq.Record will end up
// in a "reference to Record is ambiguous" depending on your JRE since it will conflict with
// java.util.Record
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.SQLDialect;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.icij.datashare.db.tables.ApiKey.API_KEY;
import static org.icij.datashare.db.tables.ApiKeyProject.API_KEY_PROJECT;
import static org.jooq.impl.DSL.using;

public class JooqApiKeyRepository implements ApiKeyRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqApiKeyRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public ApiKey get(String base64Key) {
        DSLContext ctx = using(connectionProvider, dialect);
        String hashedKey = ApiKey.DEFAULT_DIGESTER.hash(base64Key);
        ApiKeyRecord apiKeyRecord = ctx.selectFrom(API_KEY).where(API_KEY.ID.eq(hashedKey)).fetchOne();
        if (apiKeyRecord == null) {
            return null;
        }
        return toApiKeys(ctx, List.of(apiKeyRecord)).get(0);
    }

    @Override
    public ApiKey get(User user) {
        List<ApiKey> apiKeys = getAll(user);
        if (apiKeys.isEmpty()) {
            return null;
        }
        return apiKeys.get(0);
    }

    @Override
    public List<ApiKey> getAll(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        List<ApiKeyRecord> records = ctx.selectFrom(API_KEY).where(API_KEY.USER_ID.eq(user.id))
                                        .orderBy(API_KEY.CREATION_DATE.desc(), API_KEY.ID.desc()).fetch();
        return toApiKeys(ctx, records);
    }

    @Override
    public boolean delete(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.transactionResult(config -> {
            DSLContext tx = using(config);
            return deleteAll(tx, user);
        });
    }

    @Override
    public boolean delete(User user, String hashedKey) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.transactionResult(config -> {
            DSLContext tx = using(config);
            Condition isKey = API_KEY.ID.eq(hashedKey);
            Condition ofUser = API_KEY.USER_ID.eq(user.id);
            int deleted = tx.deleteFrom(API_KEY).where(isKey.and(ofUser)).execute();
            if (deleted == 0) {
                return false;
            }
            tx.deleteFrom(API_KEY_PROJECT).where(API_KEY_PROJECT.API_KEY_ID.eq(hashedKey)).execute();
            return true;
        });
    }

    @Override
    public boolean save(ApiKey apiKey) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.transactionResult(config -> {
            DSLContext tx = using(config);
            return insert(tx, apiKey);
        });
    }

    @Override
    public boolean replace(ApiKey apiKey) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.transactionResult(config -> {
            DSLContext tx = using(config);
            deleteAll(tx, apiKey.getUser());
            return insert(tx, apiKey);
        });
    }

    // ----------------
    private boolean deleteAll(DSLContext tx, User user) {
        Select<Record1<String>> userKeyIds = tx.select(API_KEY.ID).from(API_KEY).where(API_KEY.USER_ID.eq(user.id));
        tx.deleteFrom(API_KEY_PROJECT).where(API_KEY_PROJECT.API_KEY_ID.in(userKeyIds)).execute();
        return tx.deleteFrom(API_KEY).where(API_KEY.USER_ID.eq(user.id)).execute() > 0;
    }

    private boolean insert(DSLContext tx, ApiKey apiKey) {
        Date nowDate = DatashareTime.getInstance().now();
        LocalDateTime now = toUtcLocalDateTime(nowDate);
        LocalDateTime expirationDate = toUtcLocalDateTime(apiKey.getExpirationDate());
        int inserted =
                tx.insertInto(API_KEY, API_KEY.ID, API_KEY.USER_ID, API_KEY.CREATION_DATE, API_KEY.EXPIRATION_DATE)
                  .values(apiKey.getId(), apiKey.getUser().id, now, expirationDate).execute();
        for (String project : apiKey.getProjects()) {
            tx.insertInto(API_KEY_PROJECT, API_KEY_PROJECT.API_KEY_ID, API_KEY_PROJECT.PRJ_ID)
              .values(apiKey.getId(), project).execute();
        }
        return inserted > 0;
    }

    private List<ApiKey> toApiKeys(DSLContext ctx, List<ApiKeyRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> ids = records.stream().map(ApiKeyRecord::getId).collect(Collectors.toList());
        Condition ofKeys = API_KEY_PROJECT.API_KEY_ID.in(ids);
        Map<String, List<String>> projectsByKey = ctx.selectFrom(API_KEY_PROJECT).where(ofKeys)
                                                     .fetchGroups(API_KEY_PROJECT.API_KEY_ID, API_KEY_PROJECT.PRJ_ID);
        List<ApiKey> apiKeys = new ArrayList<>();
        for (ApiKeyRecord apiKeyRecord : records) {
            List<String> projects = projectsByKey.getOrDefault(apiKeyRecord.getId(), List.of());
            apiKeys.add(createApiKey(apiKeyRecord, projects));
        }
        return apiKeys;
    }

    private ApiKey createApiKey(ApiKeyRecord apiKeyRecord, List<String> projects) {
        User user = new User(apiKeyRecord.getUserId());
        Date creationDate = toDate(apiKeyRecord.getCreationDate());
        Date expirationDate = toDate(apiKeyRecord.getExpirationDate());
        return new DatashareApiKey(apiKeyRecord.getId(), user, creationDate, expirationDate, projects);
    }

    private static Date toDate(LocalDateTime utcDateTime) {
        if (utcDateTime == null) {
            return null;
        }
        Instant instant = utcDateTime.toInstant(ZoneOffset.UTC);
        return Date.from(instant);
    }

    private static LocalDateTime toUtcLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        Instant instant = date.toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
