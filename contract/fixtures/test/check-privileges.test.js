// check-privileges.test.js
//
// A regression test for check-privileges.js. It needs no MongoDB server.
// It loads the real script source in a VM context, and gives the script a
// test double for the mongosh objects `db` and `print`. The double throws
// a chosen error from one probe, so the test proves the rule of the
// errorInfo helper: a server error keeps its code, its code name, and its
// text; each other error is a driver error, and it holds no host name.
//
// Run: node --test contract/fixtures/test/*.test.js

const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "check-privileges.js");
const FIXTURE_PATH = path.join(__dirname, "..", "connection-status-m0.json");
const SCRIPT_SOURCE = fs.readFileSync(SCRIPT_PATH, "utf8");
const FIXTURE = JSON.parse(fs.readFileSync(FIXTURE_PATH, "utf8"));

const EVENTS_COLLECTION = "octometer_events";
const OTHER_COLLECTION = "octometer_probe_other";

// Builds a test double for `db`. `throwOn` names the one probe that must
// throw the given error. Each other probe succeeds with an empty result.
function buildDb(throwOn) {
  return {
    getName() {
      return "testdb";
    },
    runCommand(cmd) {
      if (cmd.connectionStatus) {
        if (throwOn.connectionStatus) throw throwOn.connectionStatus;
        return {
          ok: 1,
          authInfo: {
            authenticatedUsers: [{ user: "reader", db: "admin" }],
            authenticatedUserRoles: [],
            authenticatedUserPrivileges: [],
          },
        };
      }
      if (cmd.listCollections) {
        if (throwOn.listCollections) throw throwOn.listCollections;
        return { ok: 1, cursor: { firstBatch: [] } };
      }
      throw new Error("test double: unexpected command " + JSON.stringify(cmd));
    },
    getCollection(name) {
      return {
        find() {
          return {
            limit() {
              return {
                toArray() {
                  if (name === EVENTS_COLLECTION && throwOn.findEvents) {
                    throw throwOn.findEvents;
                  }
                  if (name === OTHER_COLLECTION && throwOn.findOther) {
                    throw throwOn.findOther;
                  }
                  return [];
                },
              };
            },
          };
        },
        insertOne() {
          if (name === EVENTS_COLLECTION && throwOn.insertEvents) {
            throw throwOn.insertEvents;
          }
          return { insertedId: "fake-probe-id" };
        },
        deleteOne() {
          return { deletedCount: 1 };
        },
      };
    },
  };
}

// Runs the real script source with a mock db, and gives back the parsed
// output. The mock print() function captures the one printed line.
function runScript(throwOn) {
  let printed = null;
  const sandbox = {
    db: buildDb(throwOn),
    print(s) {
      printed = s;
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(SCRIPT_SOURCE, sandbox, { filename: "check-privileges.js" });
  assert.equal(typeof printed, "string", "the script must print one line");
  assert.doesNotThrow(() => JSON.parse(printed), "the line must be valid JSON");
  return { printed, result: JSON.parse(printed) };
}

test("a server error (code 8000, AtlasError) keeps code, codeName, and errmsg", () => {
  const e = { code: 8000, codeName: "AtlasError", errmsg: "user is not allowed to do action [insert] on [exampledb.octometer_events]" };
  const { result } = runScript({ insertEvents: e });
  assert.deepEqual(result.insertEvents.error, {
    code: 8000,
    codeName: "AtlasError",
    errmsg: "user is not allowed to do action [insert] on [exampledb.octometer_events]",
  });
});

test("a server error (code 13, Unauthorized) keeps code, codeName, and errmsg", () => {
  const e = { code: 13, codeName: "Unauthorized", errmsg: "not authorized on testdb to execute command insert" };
  const { result } = runScript({ insertEvents: e });
  assert.equal(result.insertEvents.error.code, 13);
  assert.equal(result.insertEvents.error.codeName, "Unauthorized");
  assert.equal(result.insertEvents.error.errmsg, "not authorized on testdb to execute command insert");
  assert.equal(result.insertEvents.error.driverError, undefined);
});

test("a MongoServerSelectionError shape (code undefined, a host in the message) gives a driver error", () => {
  const e = new Error("connect ECONNREFUSED 127.0.0.1:27099");
  // code stays undefined, as the real driver leaves it for this error class.
  const { printed, result } = runScript({ findEvents: e });
  assert.deepEqual(result.findEvents.error, { code: null, codeName: null, errmsg: "", driverError: true });
  assert.ok(!printed.includes("127.0.0.1"), "the output must hold no IP address");
  assert.ok(!printed.includes("27099"), "the output must hold no port");
});

test("a Node system error (a text code, a host in the message) gives a driver error", () => {
  const e = new Error("querySrv ECONNREFUSED _mongodb._tcp.octo-cluster.abcde.mongodb.net");
  e.code = "ECONNREFUSED"; // a Node system error carries a string code.
  const { printed, result } = runScript({ findEvents: e });
  assert.deepEqual(result.findEvents.error, { code: null, codeName: null, errmsg: "", driverError: true });
  assert.ok(!printed.includes("octo-cluster"), "the output must hold no cluster host name");
  assert.ok(!printed.includes("mongodb.net"), "the output must hold no Atlas domain name");
});

test("code: 0 with a codeName gives a server error, and the host is still removed from the text", () => {
  const e = {
    code: 0,
    codeName: "OK",
    errmsg: "socket exception [CONNECT_ERROR] server [cluster0-shard-00-01.abcde.mongodb.net:27017]",
  };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.code, 0);
  assert.equal(result.findEvents.error.codeName, "OK");
  assert.equal(result.findEvents.error.driverError, undefined);
  assert.ok(result.findEvents.error.errmsg.includes("socket exception"), "the text must stay, minus the host");
  assert.ok(!result.findEvents.error.errmsg.includes("cluster0-shard-00-01"), "the host must be gone");
  assert.ok(!printed.includes("cluster0-shard-00-01"), "the output must hold no host name");
  assert.ok(!printed.includes(":27017"), "the output must hold no port");
});

// The four holes of redactHost that the second security review of #85
// found. Each test names the hole, and fails against the code before the
// fix.

test("a bare IPv4 address with no port is removed from a server errmsg", () => {
  const e = { code: 6, codeName: "HostUnreachable", errmsg: "getaddrinfo ENOTFOUND 10.20.30.40" };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "getaddrinfo ENOTFOUND <host>");
  assert.ok(!printed.includes("10.20.30.40"), "the output must hold no IPv4 address");
});

test("an IPv6 address in brackets, with a port, is removed from a server errmsg", () => {
  const e = { code: 6, codeName: "HostUnreachable", errmsg: "connection 1 to [::1]:27017 closed" };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "connection 1 to <host> closed");
  assert.ok(!printed.includes("::1"), "the output must hold no IPv6 address");
  assert.ok(!printed.includes("27017"), "the output must hold no port");
});

test("a full IPv6 address in brackets, with a port, is removed from a server errmsg", () => {
  const e = { code: 6, codeName: "HostUnreachable", errmsg: "connection 3 to [2001:db8::1]:27017 closed" };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "connection 3 to <host> closed");
  assert.ok(!printed.includes("2001:db8"), "the output must hold no IPv6 address");
});

test("an Atlas host name in upper case is removed from a server errmsg", () => {
  const e = {
    code: 6,
    codeName: "HostUnreachable",
    errmsg: "host CLUSTER0-SHARD-00-01.ABCDE.MONGODB.NET is down",
  };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "host <host> is down");
  assert.ok(!printed.includes("CLUSTER0"), "the output must hold no upper-case cluster name");
});

test("redactHost runs before the 300-character cut, so a host at the limit is fully removed", () => {
  const prefix = "a".repeat(273) + " ";
  const host = "ac-abc123-shard-00-01.xyz.mongodb.net:27017";
  const e = { code: 6, codeName: "HostUnreachable", errmsg: prefix + host + " was cleared" };
  const { printed, result } = runScript({ findEvents: e });
  assert.ok(
    !result.findEvents.error.errmsg.includes("ac-abc123-shard-00-01"),
    "the kept errmsg must hold no part of the host name"
  );
  assert.ok(!printed.includes("ac-abc123-shard-00-01"), "the output must hold no part of the host name");
});

// Regression tests. Each case worked before the fix of the four holes
// above, and it must still work after the fix.

test("an IPv4 address with a port is removed from a server errmsg", () => {
  const e = { code: 6, codeName: "HostUnreachable", errmsg: "connection 1 to 10.20.30.40:27017 timed out" };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "connection 1 to <host> timed out");
  assert.ok(!printed.includes("10.20.30.40"));
});

test("an Atlas host name in lower case, with no port, is removed from a server errmsg", () => {
  const e = {
    code: 6,
    codeName: "HostUnreachable",
    errmsg: "could not reach cluster0-shard-00-01.abcde.mongodb.net",
  };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "could not reach <host>");
  assert.ok(!printed.includes("cluster0-shard-00-01"));
});

test("two host names in one errmsg are both removed", () => {
  const e = {
    code: 6,
    codeName: "HostUnreachable",
    errmsg:
      "failover from cluster0-shard-00-01.abcde.mongodb.net:27017 to cluster0-shard-00-02.abcde.mongodb.net:27017",
  };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "failover from <host> to <host>");
  assert.ok(!printed.includes("cluster0-shard-00"));
});

test("a plain host name with a letter and a port is removed, for example mongo1:27017", () => {
  const e = { code: 6, codeName: "HostUnreachable", errmsg: "could not reach mongo1:27017" };
  const { printed, result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "could not reach <host>");
  assert.ok(!printed.includes("mongo1:27017"));
});

test("a plain time value inside a server errmsg is not mistaken for a host", () => {
  const e = { code: 6, codeName: "HostUnreachable", errmsg: "operation timed out at 2026-09-21T10:30:00Z" };
  const { result } = runScript({ findEvents: e });
  assert.equal(result.findEvents.error.errmsg, "operation timed out at 2026-09-21T10:30:00Z");
});

test("the Atlas allowed-action text stays as it is", () => {
  const e = {
    code: 8000,
    codeName: "AtlasError",
    errmsg: "user is not allowed to do action [find] on [exampledb.octometer_probe_other]",
  };
  const { result } = runScript({ findEvents: e });
  assert.equal(
    result.findEvents.error.errmsg,
    "user is not allowed to do action [find] on [exampledb.octometer_probe_other]"
  );
});

test("the two errors of connection-status-m0.json come back byte for byte", () => {
  const insertE = {
    code: FIXTURE.insertEvents.error.code,
    codeName: FIXTURE.insertEvents.error.codeName,
    errmsg: FIXTURE.insertEvents.error.errmsg,
  };
  const findOtherE = {
    code: FIXTURE.findOther.error.code,
    codeName: FIXTURE.findOther.error.codeName,
    errmsg: FIXTURE.findOther.error.errmsg,
  };
  const { result } = runScript({ insertEvents: insertE, findOther: findOtherE });
  assert.deepEqual(result.insertEvents.error, FIXTURE.insertEvents.error);
  assert.deepEqual(result.findOther.error, FIXTURE.findOther.error);
});
