# Task 2 Report: UserAdminServiceImpl — real get/list/update; listUsers in UserStore

## Status: DONE

## Commits
- `8cd9c2d2a` — feat(user-admin): implement get, list, update in UserAdminServiceImpl; add listUsers to UserStore

## Test Results

### UserAdminServiceImplTest
Command: `mvn test -pl datashare-app -Dtest=UserAdminServiceImplTest`
```
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
(17 pre-existing + 6 new)

### Regression checks
Command: `mvn test -pl datashare-app -Dtest=UserResourceTest,UsersInDbTest`
```
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0  (UserResourceTest)
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0  (UsersInDbTest)
BUILD SUCCESS
```

## Files Changed

- `datashare-app/src/main/java/org/icij/datashare/session/UserStore.java` — added `List<User> listUsers()` method
- `datashare-app/src/main/java/org/icij/datashare/session/UsersInDb.java` — stub `listUsers()` throws `UnsupportedOperationException("not yet implemented")`; added `java.util.List` import
- `datashare-app/src/main/java/org/icij/datashare/session/UsersInRedis.java` — permanent `listUsers()` throws `UnsupportedOperationException("listUsers is not supported by Redis session store")`; added `java.util.List` import
- `datashare-app/src/main/java/org/icij/datashare/user/admin/UserAdminServiceImpl.java` — replaced three stubs; `get` calls `userStore.find(login)`, casts to `User`, throws `UserNotFoundException` if null; `list` delegates to `userStore.listUsers()`; `update` fetches existing, applies partial-update (null fields fall back to existing values), hashes password if provided, saves, returns `UserCreated`
- `datashare-app/src/test/java/org/icij/datashare/user/admin/UserAdminServiceImplTest.java` — added 6 tests: `test_get_returns_user_when_found`, `test_get_throws_when_user_not_found`, `test_list_delegates_to_user_store`, `test_update_throws_when_user_not_found`, `test_update_changes_email_and_name`, `test_update_hashes_password_when_provided`, `test_update_preserves_fields_not_in_request`, `test_update_replaces_groups_when_provided`

## Self-Review Notes

1. **Cast safety**: `userStore.find()` returns `net.codestory.http.security.User`. All concrete implementations (`UsersInDb`, `UsersInRedis`) return `DatashareUser`, which extends `org.icij.datashare.user.User`. The cast `(User) found` is safe for any real store. The mock in tests returns `new DatashareUser(...)` which also extends `User`, so tests are safe too.

2. **Partial update semantics**: `null` fields in `UserUpdateRequest` preserve the existing user values. This is a natural merge rather than a full replace.

3. **UsersInDb.listUsers() uses qualified name**: Since the file already imports both `net.codestory.http.security.User` and uses `org.icij.datashare.user.User` qualified throughout, the new method uses `List<org.icij.datashare.user.User>` consistently with the file's pattern.

4. **`Repository` has no `listUsers`** method yet — intentional per spec; Task 3 will add it and replace the stub in `UsersInDb`.

---

## Code Review Fix (post-review)

### Changes made

**`UserAdminServiceImpl.java`**
- Fixed `update()`: replaced `if (req.password() != null && !req.password().isEmpty())` with a two-branch check that throws `ValidationException("password", "password cannot be empty")` when password is non-null but empty, then hashes and stores it otherwise.

**`UserAdminServiceImplTest.java`**
- Removed unused `assertThrows` static import.
- Added `import java.util.HashMap`.
- Replaced `test_get_throws_when_user_not_found`: now uses try/catch and asserts `e.getMessage().contains("ghost")`.
- Replaced `test_update_throws_when_user_not_found`: now uses try/catch and asserts `e.getMessage().contains("ghost")`; also catches `ValidationException` and fails on it.
- Added new test `test_update_throws_validation_when_empty_password`: verifies that passing `""` as password in `update()` throws `ValidationException` with field `"password"`.

### Test command + result

```
mvn test -pl datashare-app -Dtest=UserAdminServiceImplTest -q 2>&1 | tail -10
```

Result: BUILD SUCCESS — all tests passed (no failures, no errors).
