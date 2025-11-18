package org.icij.datashare.db;

import com.rometools.utils.Lists;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.User;
import org.icij.datashare.db.tables.records.UserPolicyRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static org.icij.datashare.db.Tables.USER_POLICY;
import static org.jooq.impl.DSL.using;

public class JooqUserPolicyRepository implements UserPolicyRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqUserPolicyRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
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
        DSLContext ctx = using(connectionProvider, dialect);
        var rec = ctx.selectFrom(USER_POLICY)
                .where(USER_POLICY.USER_ID.eq(user.id)
                        .and(USER_POLICY.PRJ_ID.eq(projectId)))
                .fetchOne();
        return rec != null ? fromRecord(rec) : null;
    }

    @Override
    public List<UserPolicy> list(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.selectFrom(USER_POLICY)
                .where(USER_POLICY.USER_ID.eq(user.id))
                .fetch()
                .stream()
                .map(JooqUserPolicyRepository::fromRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserPolicy> getAll() {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.selectFrom(USER_POLICY)
                .fetch()
                .stream()
                .map(JooqUserPolicyRepository::fromRecord)
                .collect(Collectors.toList());
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
}
