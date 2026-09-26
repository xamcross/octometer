Approved by the owner on 2026-09-27 (issue #42). The legal basis of section 6 waits for issue #41.

This text is in ASD-STE100 Simplified Technical English. It is not legal advice.

## 1. The controller

The controller of this app is `<owner name, contact address>`, established in Ukraine.

## 2. The click data we record

We record your clicks so we can improve this app. Each record holds these
items:

- the time of the click;
- the name of the element;
- the path of the page;
- the session id of your browser tab.

The stored path is the pattern of the page route, or the word `/other`.

The first record of a session also holds the host of the page that sent
you here. The stored value is one of three words: `google.com`, `bing.com`,
or `other`.

A record never holds your name or your email address.

A request with no signed-in user may use your IP address to limit abuse.
We keep it in memory only. A counter of one minute always applies. A day
counter keeps a key of your address for a maximum of 24 hours, when the
app records anonymous clicks.

## 3. Your account id

A signed-in user gets a pseudonymous account id in each record. This id is
never your email address.

## 4. The purpose

We record clicks to build usage statistics of the app. We do not sell
this data. We do not give this data to an advertiser.

## 5. How long we keep your record

The app log keeps a record for 30 days. This time is the default of the
setting `OCTOMETER_RETENTION_DAYS`.

The owner's own monitor keeps a copy of your record for 395 days. This
time is the current default, not a final decision. A reviewer proposed a
shorter time, 90 days, for a record tied to your account. Issue #60 sets
the final time.

## 6. The legal basis

[The owner states the legal basis here; issue #41.]

Issue #41 also holds the open decision on the consent method.

## 7. The processors

We use MongoDB Atlas to store the record. We use `<the hosting provider
of this app>` to run this app. Each processor sits outside the EU.

The owner's monitor also reads the record, on the owner's own Windows
computer (design decision D11). The owner is the controller of section
1, so the monitor is no processor.

The EU-US Data Privacy Framework adequacy decision covers MongoDB Inc.
in the USA. No EU adequacy decision covers Ukraine. For Ukraine we use
the safeguards named at `<link to the safeguards list>`. You may ask us
for a copy of them.

## 8. Your rights

You may ask us to show, correct, delete, or export your record. You may
also ask us to pause its use, or to object to its use.

You may complain to your national data protection authority. Contact:
`<owner contact address>`.

## 9. Your account deletion

When you delete your account, the app deletes each record of your account
id at once. It also deletes the anonymous records of each session of your
account id. A sign-in in the same browser tab connects the earlier
anonymous clicks of that tab to your account.

The owner's own monitor keeps its own copy until the owner runs a separate
erasure step, after one poll cycle of the monitor.

<!--
Sources:
contract/README.md, rules C4, C6, C39, C40, C42, C43
docs/superpowers/specs/2026-09-21-octometer-design.md, section 4.1, section 8, section
  10, decision D5, decision D15, decision D43
docs/integration-ktor.md, lines 221 and 224-225 (`OCTOMETER_ANON_REQ_PER_MIN`,
  `OCTOMETER_MAX_ANON_EVENTS_PER_DAY`, `OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY`)
kit/jvm-mongo/src/main/java/octometer/kit/mongo/store/MongoEventLogStore.java, line 148
  (the default of OCTOMETER_RETENTION_DAYS, 30 days)
monitor/backend/src/main/resources/octometer-defaults.conf, line 15 (retentionDays,
  395 days)
Issue #41 (open, the legal basis and the consent method)
Issue #42 (the owner approval of this text)
Issue #60 (open, the retention time of the monitor)
The owner's comment on issue #45 (2026-09-21): #41 is parked for the pilot. Sections
  1, 4, 6, 7, and 8 of this draft take the text of pull request #87, section 7, a
  reviewed draft. This text drops each sentence on consent and on withdrawal, and it
  drops the legal basis line for the placeholder of issue #41.
Sections 2, 5, and 9 add the click data, the retention sources, and the erasure width
  that pull request #87 lacks (referrerHost of contract rule C40, decision D15, and
  contract rule C43).
The proof-check of issue #42 (2026-09-27) corrected section 7: pull request #87 named
  Cloudflare with no source, and it named Fly.io as the host of the app. The
  proof-check also added the right to object of section 8 (GDPR Article 13(2)).
The correction round of pull request #220 (2026-09-27, MAJOR 1 and MAJOR 2) corrected
  section 7 a second time. The monitor runs on the owner's own Windows computer, not
  on Fly.io (decision D11, decision D34, `monitor/backend/README.md` line 15, issue
  #38). Decision O4 names a Fly machine only as the cost that a metrics endpoint would
  add; it gives the monitor a direct read of MongoDB instead. The owner is the
  controller of section 1, so the monitor is a tool of the controller, not a
  processor. The processor list now holds MongoDB Atlas and the hosting provider of
  the app.
-->
