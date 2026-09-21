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

Before a paste into a public issue:

1. Check the exit code. The value 0 is correct.
2. Check that standard output holds exactly one line.
3. Check that standard error holds no text.
4. Read the one line. It must hold no host name and no port.
5. Paste only the JSON line.

See "A failed connection at the start" for the reason of steps 1 to 4.

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
| `driverError` | Present, with the value `true`, inside an error object of a driver error. Absent from a server error. |

An error object holds `code`, `codeName`, and `errmsg`. The `errmsg` field
stops after 300 characters. Each probe command of this script takes a
constant argument, thus the error text holds no value of a real document.

The script tests each caught error. `code` must be a number. `codeName`
must be a text. This test alone marks a server error. A server error keeps
its `code`, its `codeName`, and its `errmsg`. Decision D8 of the design
needs the error code 8000, the error code 13, and each text.

A server `errmsg` can still hold a host name. One example is a "not
primary" text. A second example is a "host unreachable" text. The script
removes each `host:port` value and each `*.mongodb.net` name from `errmsg`
before it prints the document. The limit of 300 characters gives no
protection on its own. A host name can stand near the start of such a
text.

Each other error is a driver error. A driver error has no server code.
Two examples:

- A lost connection during a probe.
- A Node system error with a text code, such as `ECONNREFUSED`.

The own text of a driver error can hold the host name and the port of the
cluster. The script drops this text. The field `errmsg` stays an empty
text. The error object holds the field `driverError: true`.

### A failed connection at the start

The run command above passes the URI as an argument. A wrong URI, or an
unreachable cluster, makes `mongosh` fail before it runs the script.
`mongosh` then prints its own error text on standard error. This text can
hold the host name and the port of the cluster. The script does not run
in this case. It cannot change this text.

Acceptance criterion 1 of issue #76 covers a driver error during the run,
for example a lost connection. It does not cover this earlier failure.
The checklist above under "Run command" protects the owner from a paste
of this text.

### Regression test

`contract/fixtures/test/check-privileges.test.js` is a Node test. It needs
no MongoDB server. It loads the real script, and it gives the script a
test double for `db`. It covers a server error, a driver error with a
text code, a driver error with no code, the value `code: 0`, and the two
errors of `connection-status-m0.json`.

Run:

```
node --test contract/fixtures/test/*.test.js
```

The plain form `node --test contract/fixtures/test` did not start the
test on the Node version of this repository (v24.13.0): the command
looked for a module named `test`, not for a folder of test files. The
glob form above works on this version.

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
