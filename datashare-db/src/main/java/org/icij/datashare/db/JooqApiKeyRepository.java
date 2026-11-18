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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import static org.icij.datashare.db.tables.ApiKey.API_KEY;
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
        return createApiKey(
                ctx.selectFrom(API_KEY).where(API_KEY.ID.eq(ApiKey.DEFAULT_DIGESTER.hash(base64Key))).fetchOne());

    }

    @Override
    public ApiKey get(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        return createApiKey(ctx.selectFrom(API_KEY).where(API_KEY.USER_ID.eq(user.id)).fetchOne());

    }

    @Override
    public boolean delete(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.deleteFrom(API_KEY).where(API_KEY.USER_ID.eq(user.id)).execute() > 0;

    }

    @Override
    public boolean save(ApiKey apiKey) {
        DSLContext ctx = using(connectionProvider, dialect);
        LocalDateTime now = new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime();
        LocalDateTime expirationDate = toUtcLocalDateTime(apiKey.getExpirationDate());
        return ctx.insertInto(API_KEY, API_KEY.ID, API_KEY.USER_ID, API_KEY.CREATION_DATE, API_KEY.EXPIRATION_DATE)
                  .values(apiKey.getId(), apiKey.getUser().id, now, expirationDate).onConflict(API_KEY.USER_ID)
                  .doUpdate().set(API_KEY.ID, apiKey.getId()).set(API_KEY.CREATION_DATE, now)
                  .set(API_KEY.EXPIRATION_DATE, expirationDate).where(API_KEY.USER_ID.eq(apiKey.getUser().id))
                  .execute() > 0;

    }

    // ----------------
    private ApiKey createApiKey(ApiKeyRecord apiKeyRecord) {
        if (apiKeyRecord == null) {
            return null;
        }
        User user = new User(apiKeyRecord.getUserId());
        Date creationDate = toDate(apiKeyRecord.getCreationDate());
        Date expirationDate = toDate(apiKeyRecord.getExpirationDate());
        return new DatashareApiKey(apiKeyRecord.getId(), user, creationDate, expirationDate);
    }

    private static Date toDate(LocalDateTime utcDateTime) {
        return utcDateTime == null ? null : Date.from(utcDateTime.toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime toUtcLocalDateTime(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
}
