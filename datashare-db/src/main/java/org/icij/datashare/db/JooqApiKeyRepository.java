package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.ApiKeyRecord;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Date;

import static org.icij.datashare.db.tables.ApiKey.API_KEY;

public class JooqApiKeyRepository implements ApiKeyRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqApiKeyRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    public ApiKey get(String base64Key) {
        return createApiKey(DSL.using(connectionProvider, dialect).
                selectFrom(API_KEY).
                where(API_KEY.ID.eq(ApiKey.HASHER.hash(base64Key))).fetchOne());
    }

    @Override
    public ApiKey get(User user) {
        return createApiKey(DSL.using(connectionProvider, dialect).
                selectFrom(API_KEY).
                where(API_KEY.USER_ID.eq(user.id)).fetchOne());
    }

    public boolean delete(User user) {
        return DSL.using(connectionProvider,dialect).deleteFrom(API_KEY)
                .where(API_KEY.USER_ID.eq(user.id)).execute() > 0;
    }

    public boolean save(ApiKey apiKey) {
        return DSL.using(connectionProvider, dialect).insertInto(API_KEY).
                values(apiKey.getId(), apiKey.getUser().id, new Timestamp((DatashareTime.getInstance().currentTimeMillis()))).
                onConflict(API_KEY.USER_ID).doUpdate().
                    set(API_KEY.ID, apiKey.getId()).
                    set(API_KEY.CREATION_DATE, new Timestamp((DatashareTime.getInstance().currentTimeMillis()))).
                    where(API_KEY.USER_ID.eq(apiKey.getUser().id)).execute() > 0;
    }

    // ----------------
    private ApiKey createApiKey(ApiKeyRecord apiKeyRecord) {
        return apiKeyRecord != null ? new DatashareApiKey(apiKeyRecord.getId(), new User(apiKeyRecord.getUserId()), Date.from(apiKeyRecord.getCreationDate().toInstant())) : null;
    }
}
