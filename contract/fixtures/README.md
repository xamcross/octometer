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

Replace `<uri>` with the full connection string of the database user. The
URI must end with `/<database>`. The Atlas "Connect with mongosh" panel
gives a URI without a database name, thus add the name by hand before you
run the script. Save the output as
`contract/fixtures/connection-status-m0.json`.

### Fields of the output

| Field | Meaning |
|---|---|
| `database` | The database name. The script reads it from the URI with `db.getName()`. |
| `databaseWarning` | A short text when `database` is `test`. This value is the default of the driver when the URI holds no database path, not proof of a real database. Add the database name to the URI, then run the script again. |
| `eventsCollection` | The name of the collection under test. The value is the constant `octometer_events`. |
| `otherCollection` | The name of a second collection. The value is the constant `octometer_probe_other`. This collection does not need to exist. |
| `connectionStatus` | The result of `connectionStatus` with `showPrivileges: true`. The script replaces each user name in `authInfo.authenticatedUsers` with the fixed text `REDACTED`. When `authInfo.authenticatedUserPrivileges` is absent, or the array is empty, the cluster gives no usable privilege list through this command. |
| `listCollections` | The result of `listCollections` with `authorizedCollections: true, nameOnly: true`, reduced to the list of collection names and types that the user sees. |
| `findEvents` | The result of one `find` with a limit of 1 on `octometer_events`. Holds 1 when the collection holds a document, and 0 when the collection is empty. Holds the field names of the first document, never a value. |
| `insertEvents` | The result of one `insertOne` on `octometer_events`. Holds `insertWorked`. When the insert fails, it holds the error. When the insert works, it holds `probeMarker`, `probeDeleted`, and `deleteError`. |
| `findOther` | The result of one `find` on `octometer_probe_other`. Holds the document count on success, or the error on failure. |

An error object holds `code`, `codeName`, and `errmsg`. The `errmsg` field
stops after 300 characters. Each probe command of this script takes a
constant argument, thus the error text holds no value of a real document.

A driver error has no `code`, for example a lost connection during a probe.
Its text can hold the host name and the port of the cluster. The script
drops this text: `errmsg` stays an empty text, and the error object holds
the field `driverError: true`. A server error keeps its `code`, `codeName`,
and `errmsg`, because decision D8 of the design needs the error code 8000
and its text.

### A failed connection at the start

When the URI itself is wrong, or the cluster is not reachable, `mongosh`
fails before it runs the script. `mongosh` then prints its own error text
on standard error, and this text can hold the host name and the port of
the cluster. The script does not run in this case, so it cannot change
this text.

Before the owner pastes the output into a public issue, the owner must
read the full terminal output, not only the JSON line. When the command
fails (a non-zero exit code, or a line before or after the JSON line), the
owner removes the host name and the port by hand, or pastes only the JSON
line and drops the rest.

### The probe document

When `insertOne` on `octometer_events` works, the script inserts one
document with the field `octometerProbeMarker`, then deletes it again. The
output field `probeDeleted` states if the delete worked.

A value of `probeDeleted: false` means the document stays in the
collection. The role then holds `insert` and not `remove`. The owner must
remove the document with the marker value of `probeMarker` by hand:

```
db.octometer_events.deleteMany({ octometerProbeMarker: "octometer-check-privileges-probe" })
```

### The privilege check of D9

- Check 1 (`listCollections`) must return exactly one collection:
  `octometer_events`. A second collection in the list means the role is too
  wide.
- Check 2 (`connectionStatus`) must show one privilege: `find` on
  `octometer_events`. An absent or an empty `authenticatedUserPrivileges`
  array means check 1 alone must decide, since the cluster gives no usable
  privilege list.

### Result on Atlas M0

The owner ran the script on a real Atlas M0 cluster on 2026-09-21, with the
user and the role of section 4.4. The output is in
`connection-status-m0.json`; the file replaces the real database name with
`exampledb`.

- The cluster tier is M0. `connectionStatus` with `showPrivileges: true`
  returns `authenticatedUserPrivileges`. The array is complete and exact:
  one resource (the one collection), with the one action `find`.
  `authenticatedUserRoles` shows the one role `octometerEventReader`.
- `find` on `octometer_events` works. `insertOne` on `octometer_events`
  fails, and `find` on the second collection fails. Each failure has the
  code 8000, the code name `AtlasError`, and the text "user is not allowed
  to do action [...]". A local MongoDB gives the code 13 (`Unauthorized`)
  for the same case.
- `listCollections` with `authorizedCollections: true, nameOnly: true`
  returns an empty list, because the collection did not exist at the time
  of the run. The result on M0 for a user with only `find` when the
  collection exists is not known yet.
