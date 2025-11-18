package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.UserInventoryRecord;
import org.icij.datashare.user.User;
import org.jooq.Record;

import static org.icij.datashare.db.tables.DocumentUserRecommendation.DOCUMENT_USER_RECOMMENDATION;
import static org.icij.datashare.db.tables.UserInventory.USER_INVENTORY;

public class PersistenceMappings {
    static User createUserFrom(Record record) {
        if (record == null) {
            return null;
        }
        UserInventoryRecord userRecord = record.into(USER_INVENTORY);
        if (userRecord.getId() == null) {
            return new User(record.into(DOCUMENT_USER_RECOMMENDATION).getUserId());
        }
        return new User(userRecord.getId(), userRecord.getName(), userRecord.getEmail(), userRecord.getProvider(), userRecord.getDetails());
    }
}
