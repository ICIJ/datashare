package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.UserInventoryRecord;
import org.icij.datashare.db.tables.records.UserPolicyRecord;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserRepository;
import org.jooq.DSLContext;
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.Result;
import org.jooq.SQLDialect;

import javax.sql.DataSource;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.icij.datashare.db.Tables.USER_POLICY;
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

    private static User createUserFrom(UserInventoryRecord record) {
        if (record == null) {
            return null; // could be NullPointerException
        }
        // record not found
        UserInventoryRecord userRecord = record.into(USER_INVENTORY);
        if (userRecord.getId() == null) {
            return null;
        }
        return new User(userRecord.getId(), userRecord.getName(), userRecord.getEmail(), userRecord.getProvider(), userRecord.getDetails());
    }

    private static UserPolicy fromRecord(UserPolicyRecord r) {
        List<Role> roles = new LinkedList<>();
        if (r.getRead()) roles.add(Role.READER);
        if (r.getWrite()) roles.add(Role.WRITER);
        if (r.getAdmin()) roles.add(Role.ADMIN);
        return new UserPolicy(
                r.getUserId(),
                r.getPrjId(),
                roles.toArray(new Role[0])
        );
    }

    @Override
    public UserPolicy get(User user, String projectId) {
        return get(user.getId(),projectId);
    }
    @Override
    public UserPolicy get(String userId, String projectId) {
        DSLContext ctx = using(connectionProvider, dialect);
        var rec = ctx.selectFrom(USER_POLICY)
                .where(USER_POLICY.USER_ID.eq(userId)
                        .and(USER_POLICY.PRJ_ID.eq(projectId)))
                .fetchOne();
        return rec != null ? fromRecord(rec) : null;
    }

    @Override
    public List<UserPolicy> getPolicies(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.selectFrom(USER_POLICY)
                .where(USER_POLICY.USER_ID.eq(user.id))
                .fetch()
                .stream()
                .map(JooqUserRepository::fromRecord)
                .toList();
    }

    @Override
    public Stream<UserPolicy> getAll() {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.selectFrom(USER_POLICY)
                .fetch()
                .stream()
                .map(JooqUserRepository::fromRecord);
    }


    @Override
    public boolean save(UserPolicy policy) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.insertInto(USER_POLICY)
                .columns(USER_POLICY.USER_ID, USER_POLICY.PRJ_ID, USER_POLICY.READ, USER_POLICY.WRITE, USER_POLICY.ADMIN)
                .values(policy.userId(), policy.projectId(), policy.reader(), policy.writer(), policy.admin())
                .onConflict(USER_POLICY.USER_ID, USER_POLICY.PRJ_ID)
                .doUpdate()
                .set(USER_POLICY.READ, policy.reader())
                .set(USER_POLICY.WRITE, policy.writer())
                .set(USER_POLICY.ADMIN, policy.admin())
                .execute() > 0;
    }

    @Override
    public boolean delete(User user, String projectId) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.deleteFrom(USER_POLICY)
                .where(USER_POLICY.USER_ID.eq(user.id).and(USER_POLICY.PRJ_ID.eq(projectId)))
                .execute() > 0;
    }

    @Override
    public User getAllPolicies(String userId) {
        DSLContext ctx = using(connectionProvider, dialect);
        Map<UserInventoryRecord, Result<UserPolicyRecord>> userInventoryRecordResultMap =
                ctx.select().from(USER_INVENTORY).leftJoin(USER_POLICY).on(USER_POLICY.USER_ID.eq(USER_INVENTORY.ID))
                        .and(USER_POLICY.USER_ID.eq(userId)).fetchGroups(USER_INVENTORY, USER_POLICY);
        Set<UserPolicy> policies = userInventoryRecordResultMap.values().iterator().next().stream().map(JooqUserRepository::fromRecord).collect(Collectors.toSet());
        UserInventoryRecord userRecord = userInventoryRecordResultMap.keySet().iterator().next();
        return new User(userRecord.getId(), userRecord.getName(), userRecord.getEmail(), userRecord.getProvider(), userRecord.getDetails(), policies);
    }

}