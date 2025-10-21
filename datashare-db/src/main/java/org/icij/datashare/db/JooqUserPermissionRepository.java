package org.icij.datashare.db;

import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPermission;
import org.icij.datashare.user.UserPermissionRepository;
import org.icij.datashare.db.tables.records.UserPermissionRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

import static org.icij.datashare.db.Tables.USER_PERMISSION;
import static org.jooq.impl.DSL.using;

public class JooqUserPermissionRepository implements UserPermissionRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqUserPermissionRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    private static UserPermission fromRecord(UserPermissionRecord r) {
        return new UserPermission(
                r.getUserId(),
                r.getPrjId(),
                Boolean.TRUE.equals(r.getRead()),
                Boolean.TRUE.equals(r.getWrite()),
                Boolean.TRUE.equals(r.getAdmin())
        );
    }

    @Override
    public UserPermission get(User user, String projectId) {
        DSLContext ctx = using(connectionProvider, dialect);
        var rec = ctx.selectFrom(USER_PERMISSION)
                .where(USER_PERMISSION.USER_ID.eq(user.id)
                        .and(USER_PERMISSION.PRJ_ID.eq(projectId)))
                .fetchOne();
        return rec != null ? fromRecord(rec) : null;
    }

    @Override
    public List<UserPermission> list(User user) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.selectFrom(USER_PERMISSION)
                .where(USER_PERMISSION.USER_ID.eq(user.id))
                .fetch()
                .stream()
                .map(JooqUserPermissionRepository::fromRecord)
                .collect(Collectors.toList());
    }

    @Override
    public boolean save(UserPermission userPermission) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.insertInto(USER_PERMISSION)
                .columns(USER_PERMISSION.USER_ID, USER_PERMISSION.PRJ_ID, USER_PERMISSION.READ, USER_PERMISSION.WRITE, USER_PERMISSION.ADMIN)
                .values(userPermission.userId(), userPermission.projectId(), userPermission.read(), userPermission.write(), userPermission.admin())
                .onConflict(USER_PERMISSION.USER_ID, USER_PERMISSION.PRJ_ID)
                .doUpdate()
                .set(USER_PERMISSION.READ, userPermission.read())
                .set(USER_PERMISSION.WRITE, userPermission.write())
                .set(USER_PERMISSION.ADMIN, userPermission.admin())
                .execute() > 0;
    }

    @Override
    public boolean delete(User user, String projectId) {
        DSLContext ctx = using(connectionProvider, dialect);
        return ctx.deleteFrom(USER_PERMISSION)
                .where(USER_PERMISSION.USER_ID.eq(user.id).and(USER_PERMISSION.PRJ_ID.eq(projectId)))
                .execute() > 0;
    }
}
