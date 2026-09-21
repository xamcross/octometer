# Fixtures

## check-privileges.js

The script `check-privileges.js` proves the privileges of a database user.
The owner runs it against the read-only user of section 4.4 of the design
document, on a real Atlas M0 cluster. The script gives the facts that
decision D9 needs: the collections that the user sees, and the privileges
that the user holds.

The script prints one JSON document to standard output. It prints no other
text. It never prints the URI, the password, or the user name of the
connection.

### Run command

```
mongosh "<uri>" --quiet --file contract/fixtures/check-privileges.js
```

Replace `<uri>` with the full connection string of the database user. Save
the output as `contract/fixtures/connection-status-m0.json`.

### Fields of the output

| Field | Meaning |
|---|---|
| `database` | The database name. The script reads it from the URI with `db.getName()`. |
| `eventsCollection` | The name of the collection under test. The value is the constant `octometer_events`. |
| `otherCollection` | The name of a second collection. The value is the constant `octometer_probe_other`. This collection does not need to exist. |
| `connectionStatus` | The result of `connectionStatus` with `showPrivileges: true`. The script replaces each user name in `authInfo.authenticatedUsers` with the fixed text `REDACTED`. When `authInfo.authenticatedUserPrivileges` is absent, the cluster does not report privileges through this command. |
| `listCollections` | The result of `listCollections` with `authorizedCollections: true, nameOnly: true`, reduced to the list of collection names and types that the user sees. |
| `findEvents` | The result of one `find` with a limit of 1 on `octometer_events`. Holds the document count and the field names of the first document, never a value. |
| `insertEvents` | The result of one `insertOne` on `octometer_events`. Holds `insertWorked` and, on failure, the error code and the error name. When the insert works, the script deletes the probe document again. |
| `findOther` | The result of one `find` on `octometer_probe_other`. Holds the document count on success, or the error code and the error name on failure. |

### The privilege check of D9

- Check 1 (`listCollections`) must return exactly one collection:
  `octometer_events`. A second collection in the list means the role is too
  wide.
- Check 2 (`connectionStatus`) must show one privilege: `find` on
  `octometer_events`. An absent `authenticatedUserPrivileges` array means
  check 1 alone must decide, since the cluster reports no privilege list.

### Result on Atlas M0

The owner run is not done yet.
