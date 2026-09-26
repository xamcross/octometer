Draft. The owner approves this text in issue #42.

This text is a draft in ASD-STE100 Simplified Technical English. It is not legal advice.

## 1. The controller

The controller of this app is `<owner name, contact address>`.

## 2. The click data we record

Each click gives one record. A record holds these items:

- the name of the element;
- the path of the page;
- the time of the click;
- the session id of your browser tab.

The first record of a session also holds the host of the page that sent you here. The
stored value is one of three words: `google.com`, `bing.com`, or `other`.

## 3. Your account id

A signed-in user gets a pseudonymous account id in each record. This id is never your
email address.

## 4. The purpose

We record clicks to build usage statistics of the app. We do not sell this data. We do
not give this data to an advertiser.

## 5. How long we keep your record

The app log keeps a record for 30 days. This time is the default of the setting
`OCTOMETER_RETENTION_DAYS`.

The owner's own monitor keeps a copy of your record for 395 days. This time is the
current default, not a final decision. A reviewer proposed a shorter time, 90 days, for
a record tied to your account. Issue #60 sets the final time.

## 6. The legal basis

[The owner states the legal basis here; issue #41.]

## 7. The consent gate

The tracker of this app starts only after your consent signal. The app then calls
`start()` on the tracker. `docs/integration-ktor.md` states the gate, in its tracker
section.

## 8. Your account deletion

When you delete your account, the app deletes each record of your account id at once.
It also deletes the anonymous records of each session of your account id. A sign-in in
the same browser tab connects the earlier anonymous clicks of that tab to your account.

The owner's own monitor keeps its own copy until the owner runs a separate erasure
step, after one poll cycle of the monitor.

<!--
Sources:
contract/README.md, rules C4, C6, C39, C40, C42, C43
docs/superpowers/specs/2026-09-21-octometer-design.md, section 4.1, section 8, section
  10, decision D5, decision D15
kit/jvm-mongo/src/main/java/octometer/kit/mongo/store/MongoEventLogStore.java, line 148
  (the default of OCTOMETER_RETENTION_DAYS, 30 days)
monitor/backend/src/main/resources/octometer-defaults.conf, line 15 (retentionDays,
  395 days)
kit/tracker/README.md, "The consent gate"
Issue #41 (open, the legal basis and the consent method)
Issue #42 (the owner approval of this text)
Issue #60 (open, the retention time of the monitor)
The owner's comment on issue #45 (2026-09-21): #41 is parked for the pilot; the draft
  privacy text of pull request #87, section 7, drops each sentence on consent and on
  withdrawal for this draft.
-->
