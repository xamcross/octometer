// check-privileges.js
//
// This script proves the privileges of the current mongosh connection.
// The owner runs it against the read-only database user of section 4.4.
//
// Run: mongosh "<uri>" --quiet --file contract/fixtures/check-privileges.js
//
// The script prints exactly one JSON document to standard output. It prints
// no other text. It never prints the URI, the password, or the user name of
// the connection. Each command runs inside a try/catch block. The script
// always prints the JSON document, even after a failed command.

const EVENTS_COLLECTION = "octometer_events";
const OTHER_COLLECTION = "octometer_probe_other";
const REDACTED_USER = "REDACTED";

const result = {
  database: null,
  eventsCollection: EVENTS_COLLECTION,
  otherCollection: OTHER_COLLECTION,
  connectionStatus: null,
  listCollections: null,
  findEvents: null,
  insertEvents: null,
  findOther: null,
};

// The database name. It comes from the URI, not from a constant.
try {
  result.database = db.getName();
} catch (e) {
  result.database = null;
}

// D9 check 2: connectionStatus with showPrivileges. The output shows if
// the privilege list is present. The user name is not part of the output.
try {
  const status = db.runCommand({ connectionStatus: 1, showPrivileges: true });
  if (status.authInfo && Array.isArray(status.authInfo.authenticatedUsers)) {
    status.authInfo.authenticatedUsers = status.authInfo.authenticatedUsers.map(
      function (u) {
        return { user: REDACTED_USER, db: u.db };
      }
    );
  }
  result.connectionStatus = status;
} catch (e) {
  result.connectionStatus = {
    ok: 0,
    error: { code: e.code || null, codeName: e.codeName || null },
  };
}

// D9 check 1: listCollections with authorizedCollections. The list must
// hold exactly the one configured collection.
try {
  const listResult = db.runCommand({
    listCollections: 1,
    authorizedCollections: true,
    nameOnly: true,
  });
  const batch = (listResult.cursor && listResult.cursor.firstBatch) || [];
  result.listCollections = {
    ok: listResult.ok,
    collections: batch.map(function (c) {
      return { name: c.name, type: c.type };
    }),
  };
} catch (e) {
  result.listCollections = {
    ok: 0,
    error: { code: e.code || null, codeName: e.codeName || null },
  };
}

// One find on octometer_events must work. The output holds the document
// count and the field names of the first document, not the values.
try {
  const docs = db.getCollection(EVENTS_COLLECTION).find({}).limit(1).toArray();
  result.findEvents = {
    ok: true,
    count: docs.length,
    fields: docs.length > 0 ? Object.keys(docs[0]) : [],
  };
} catch (e) {
  result.findEvents = {
    ok: false,
    count: null,
    fields: [],
    error: { code: e.code || null, codeName: e.codeName || null },
  };
}

// One insertOne on octometer_events must fail. The probe document holds a
// clear marker field. When the insert works, the script deletes the probe
// document again. It then reports insertWorked: true.
try {
  const marker = "octometer-check-privileges-probe";
  const insertResult = db
    .getCollection(EVENTS_COLLECTION)
    .insertOne({ octometerProbeMarker: marker });
  db.getCollection(EVENTS_COLLECTION).deleteOne({ _id: insertResult.insertedId });
  result.insertEvents = { insertWorked: true, error: null };
} catch (e) {
  result.insertEvents = {
    insertWorked: false,
    error: { code: e.code || null, codeName: e.codeName || null },
  };
}

// One find on a second collection must fail. The name of the second
// collection is a constant of this script, not a real collection of the app.
try {
  const otherDocs = db
    .getCollection(OTHER_COLLECTION)
    .find({})
    .limit(1)
    .toArray();
  result.findOther = { ok: true, count: otherDocs.length, error: null };
} catch (e) {
  result.findOther = {
    ok: false,
    count: null,
    error: { code: e.code || null, codeName: e.codeName || null },
  };
}

print(JSON.stringify(result));
