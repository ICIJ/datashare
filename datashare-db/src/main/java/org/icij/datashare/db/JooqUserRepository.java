package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.UserInventoryRecord;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserRepository;
import org.jooq.DSLContext;
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.SQLDialect;

import javax.sql.DataSource;

import static org.icij.datashare.db.PersistenceMappings.createUserFrom;
import static org.icij.datashare.db.tables.UserInventory.USER_INVENTORY;
import static org.jooq.impl.DSL.using;

public class JooqUserRepository implements UserRepository {

    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqUserRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    public boolean save(User user) {
        InsertOnDuplicateSetMoreStep<UserInventoryRecord> innerSet = using(connectionProvider, dialect).insertInto(
                        USER_INVENTORY, USER_INVENTORY.ID, USER_INVENTORY.EMAIL,
                        USER_INVENTORY.NAME, USER_INVENTORY.PROVIDER, USER_INVENTORY.DETAILS).
                values(user.id, user.email, user.name, user.provider, JsonObjectMapper.serialize(user.details)).
                onConflict(USER_INVENTORY.ID).
                doUpdate().
                set(USER_INVENTORY.EMAIL, user.email);
        return innerSet.
                set(USER_INVENTORY.DETAILS, JsonObjectMapper.serialize(user.details)).
                set(USER_INVENTORY.NAME, user.name).
                set(USER_INVENTORY.PROVIDER, user.provider).
                execute() > 0;


    }

    public User getUser(String uid) {
        DSLContext ctx = using(connectionProvider, dialect);
        return createUserFrom(ctx.selectFrom(USER_INVENTORY).where(USER_INVENTORY.ID.eq(uid)).fetchOne());
    }
}
