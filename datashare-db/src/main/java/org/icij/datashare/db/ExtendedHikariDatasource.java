package org.icij.datashare.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;
import org.sqlite.Function;
import org.sqlite.jdbc4.JDBC4Connection;

public class ExtendedHikariDatasource extends HikariDataSource {
    public ExtendedHikariDatasource(HikariConfig configuration) {
        super(configuration);
    }

    public Connection getConnection() throws SQLException {
        Connection conn = super.getConnection();
        if (conn.isWrapperFor(JDBC4Connection.class)) {
            Connection unwrapped = conn.unwrap(JDBC4Connection.class);
            addSQLiteMissingFunctions(unwrapped);
        }
        return conn;
    }

    private void addSQLiteMissingFunctions(Connection sqliteConn) throws SQLException {
        Function.create(sqliteConn, "REGEXP", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                String expression = value_text(0);
                String value = value_text(1);
                if (value == null) {
                    value = "";
                }

                Pattern pattern = Pattern.compile(expression);
                result(pattern.matcher(value).find() ? 1 : 0);
            }
        });
    }
}
