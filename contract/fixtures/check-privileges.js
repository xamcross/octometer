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
const PROBE_MARKER = "octometer-check-privileges-probe";
const ERROR_TEXT_LIMIT = 300;

// Builds one error object from a caught error. The text stops after 300
// characters. Each probe command of this script takes a constant argument,
// thus the error text holds no value of a real document.
//
// A driver error has no code. Its text can hold the host name and the port
// of the cluster. The script never prints the URI, so it also drops this
// text. It sets driverError: true in this case, so the field errmsg stays
// empty and the caller still knows that an error happened.
function errorInfo(e) {
  const hasCode = e.code !== undefined && e.code !== null;
  const info = {
    code: e.code || null,
    codeName: e.codeName || null,
    errmsg: hasCode
      ? String(e.errmsg || e.message || "").slice(0, ERROR_TEXT_LIMIT)
      : "",
  };
  if (!hasCode) {
    info.driverError = true;
  }
  return info;
}

const result = {
  database: null,
  databaseWarning: null,
  eventsCollection: EVENTS_COLLECTION,
  otherCollection: OTHER_COLLECTION,
  connectionStatus: null,
  listCollections: null,
  findEvents: null,
  insertEvents: null,
  findOther: null,
};

// The database name. It comes from the URI, not from a constant. A value
// of "test" is the default of the driver, not proof of a real database
// path. The Atlas "Connect" panel gives a URI without a database path.
try {
  result.database = db.getName();
  if (result.database === "test") {
    result.databaseWarning =
      "The URI probably has no database path. Add /<database> to the URI, then run the script again.";
  }
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
  result.connectionStatus = { ok: 0, error: errorInfo(e) };
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
  result.listCollections = { ok: 0, error: errorInfo(e) };
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
  result.findEvents = { ok: false, count: null, fields: [], error: errorInfo(e) };
}

// One insertOne on octometer_events must fail. The probe document holds a
// clear marker field. The insert and the delete each run in their own
// try/catch block, thus a failed delete never hides inside the insert
// result. When the delete does not remove the document, the output shows
// this, and the owner must remove the document with the marker by hand.
let probeId = null;
try {
  const insertResult = db
    .getCollection(EVENTS_COLLECTION)
    .insertOne({ octometerProbeMarker: PROBE_MARKER });
  probeId = insertResult.insertedId;
  result.insertEvents = { insertWorked: true, error: null };
} catch (e) {
  result.insertEvents = { insertWorked: false, error: errorInfo(e) };
}
if (probeId !== null) {
  result.insertEvents.probeMarker = PROBE_MARKER;
  try {
    const del = db.getCollection(EVENTS_COLLECTION).deleteOne({ _id: probeId });
    result.insertEvents.probeDeleted = del.deletedCount === 1;
    result.insertEvents.deleteError = null;
  } catch (e) {
    result.insertEvents.probeDeleted = false;
    result.insertEvents.deleteError = errorInfo(e);
  }
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
  result.findOther = { ok: false, count: null, error: errorInfo(e) };
}

print(JSON.stringify(result));
