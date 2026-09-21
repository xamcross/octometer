# Octometer privacy proposal: legal basis and consent for click tracking

Status: PROPOSAL of 2026-09-21. The owner has not decided yet.

This document is not legal advice. It is a technical proposal for the owner to approve or
reject. Get a lawyer's check for each point in section 9, "When to ask a lawyer".

## 1. The processing, in plain words

- Octometer records a click of a signed-in user, in the owner's own web apps.
- One event holds a time, an element name, a session id, and the internal user id of the app.
- The session id is a random UUID in `sessionStorage`. It lives for one browser tab.
- An event holds no username, no email address, and no IP address.
- An anonymous click is off by default (design decision D19).
- The tracker is first-party code in each app. It stays off until the app calls `start()`
  after a consent signal. `stop()` clears its state (decision D24).
- An event stays 30 days in the database of the app (MongoDB Atlas), and up to 395 days by
  default in the owner's local monitor.
- The monitor shows totals for each app, each user id, and each element. The purpose is the
  improvement of the owner's products.
- No processor uses the data for its own purpose. No advertiser gets the data.
- The processors are MongoDB Atlas, Fly.io, and Cloudflare.
- The owner is established in Ukraine, as one developer. Users are in Ukraine, in the EU, and
  in the USA. No app has a UK user now.
- One app is B2B: its users are employees of business customers. The other apps are for
  consumers.

## 2. The device-access rule and the legal-basis rule

### 2.1 Access to the device (ePrivacy, and Ukraine)

The ePrivacy Directive, Article 5(3), needs consent for a read or a write on a user's device,
unless an exemption applies. [VERIFIED, EUR-Lex, read 2026-09-21]

The EDPB Guidelines 2/2023 name four elements that trigger Article 5(3): a terminal device,
stored or accessed information, an electronic network, and a party other than the user.
[VERIFIED, EDPB, read 2026-09-21] The tracker's `sessionStorage` write is such a storage
operation.

The CNIL and the ICO both limit their statistics exemption to a tracker that gives
**aggregate** data, that does not identify a person, and that is not combined with other
data. [VERIFIED, CNIL Sheet 16, read 2026-09-21; ICO storage and access guidance, read
2026-09-21] Octometer stores a user id with each event, at user level. **The exemption does
not apply.** Octometer needs consent for the device access of an EU user.

Ukraine has no direct counterpart of the ePrivacy Directive. Its Law "On Personal Data
Protection" No. 2297-VI governs the personal-data question, not a separate device-storage
question. [VERIFIED, the law and its number, read 2026-09-21; NOT VERIFIED, whether a
separate Ukrainian e-communications rule also covers `sessionStorage` — ask a lawyer]

Each EU member state applies Article 5(3) through its own national law. One developer cannot
track 27 national variants, so section 4.3 proposes one rule for every EU user.

### 2.2 The legal basis (GDPR Article 6, Ukraine Article 11)

GDPR Article 6(1) lists six legal bases. Two fit a click log: (a) consent, and (f) a
legitimate interest, balanced against the user's rights. [VERIFIED, read 2026-09-21] Article
21(1) gives the user a right to object to processing under (f), including profiling.
[VERIFIED, read 2026-09-21] A per-user click history, kept up to 395 days and shown by user
id, is a form of profiling. Since the exemptions of section 2.1 exclude user-level data, the
safer basis is **consent**, not legitimate interest.

The Law of Ukraine No. 2297-VI, Article 11, lists consent, a legal permission, contract
performance, a vital interest, and a legitimate interest as grounds. [VERIFIED, read
2026-09-21] The same reasoning applies: consent is the safer ground.

### 2.3 Does the GDPR apply to this owner?

- **Article 3(2)(b)** applies the GDPR to a controller outside the EU that monitors the
  behaviour of a person in the EU. [VERIFIED, EDPB Guidelines 3/2018, read 2026-09-21] A
  timed click, tied to a user id, is behaviour tracking. **The GDPR applies to every EU user
  of the owner's apps**, even though the owner is in Ukraine.
- **Article 27** needs an EU representative for such a controller, unless the processing is
  occasional, has no large-scale special-category data, and carries a low risk. [VERIFIED,
  read 2026-09-21] Continuous click tracking is not occasional under the EDPB's narrow
  reading. **The owner likely needs an EU representative once an EU user's tracker starts.**
  This needs a lawyer's check (section 9). A representative service costs roughly EUR 200 to
  500 a year for one small business. [NOT VERIFIED — provider price pages, not an official
  source, read 2026-09-21]
- **Chapter V transfer rules** need a separate exporter and importer. A data subject who
  gives data straight to the controller is a direct collection, not a transfer. [VERIFIED,
  EDPB Guidelines 05/2021, read 2026-09-21] An EU user's click reaches the owner's app
  directly, so Chapter V does not apply to that step. The processors still need their own
  transfer tool (2.5).

### 2.4 The USA

- **CCPA/CPRA** applies above about USD 26.6 million revenue, or 100 000 California
  residents' records a year, or 50% of revenue from a sale or a share of data. [VERIFIED
  against current compliance guidance, read 2026-09-21; NOT VERIFIED against the statute
  text itself] One developer with a few small apps meets none of these. **CCPA/CPRA does not
  apply now.** Octometer also sends no data to a third party for its own purpose, so it is
  not a "sale" or a "share" even past a threshold.
- **COPPA** applies once an operator has actual knowledge of a user under 13. [VERIFIED,
  FTC guidance, read 2026-09-21] The B2B app has adult, employed users. A consumer app that
  asks for no birth date, and is not directed at children, has no such knowledge. **COPPA
  does not apply, unless an app later learns a user's age is under 13.**

### 2.5 The processors and their transfer tool

| Processor | Role | Transfer tool | Status |
|---|---|---|---|
| MongoDB Atlas | Event store, EU cluster region | EU-US Data Privacy Framework, self-certified | VERIFIED, MongoDB DPF statement, read 2026-09-21 |
| Fly.io | Runs the app backend | Standard Contractual Clauses, per its privacy policy | VERIFIED, Fly.io privacy policy, read 2026-09-21 |
| Cloudflare | Frontend hosting, network | EU-US Data Privacy Framework, SCCs as a fallback | VERIFIED, Cloudflare GDPR page, read 2026-09-21 |

## 3. Industry practice (state of the art)

| Tool | Consent or cookieless mode | Per-user id | Retention |
|---|---|---|---|
| PostHog | `cookieless_mode: 'on_reject'` blocks capture until consent | A persistent id counts as personal data even cookieless | Owner-set |
| Matomo | `requireConsent()` blocks all tracking until consent | `config_id`, a time-limited hash | Owner-set |
| Plausible | No cookie, no consent banner | None kept | Aggregate only |
| Amplitude | Data-subject-request API by user id | Persistent user id | Owner-set, EU or US residency |
| Mixpanel | Article 28 processor addendum, EU residency option | `distinct_id` | Owner-set, an auto-delete default applies |

[VERIFIED, each vendor's own documentation, read 2026-09-21]

Every tool that keeps a persistent per-user id gates its tracking behind consent. Only a tool
that drops the per-user id (Plausible, Matomo's cookieless config) skips consent. Octometer
keeps a per-user id by design, so it belongs in the first group.

## 4. The recommendation

One option for each app group, one alternative. This is a decision, not a menu.

### 4.1 Consumer apps: consent

Legal basis: **GDPR Article 6(1)(a) and Ukraine Article 11(1), consent.** Reason: the tracker
keeps a per-user click history for up to 395 days; the exemptions of section 2.1 exclude
user-level data; a consumer has no other relation to the owner that could carry a
contract-based ground. Sources: EDPB 2/2023, EDPB 05/2020, CNIL Sheet 16, ICO guidance (all
read 2026-09-21).

### 4.2 The B2B app: consent, from the employee

Legal basis: **the same, consent from the individual employee**, not the contract between the
owner and the business customer. Reason: click tracking is not necessary to run the software
the customer bought. A contract with the employer cannot give consent for the employee. The
owner must check whether the customer's own terms already name product analytics (owner step
4 of issue #41); a term there supports the notice, but it does not replace consent.

### 4.3 One global consent standard

Apply the **same consent standard to every app and every user**, not a split by country.
Reason: one developer cannot run 27 EU variants plus separate Ukraine and USA flows; the EU
and ePrivacy standard is the strictest of the three regions in scope, so it also satisfies
Ukraine and the USA; the industry (section 3) ships one consent-gated mode for every visitor,
not a geo split. **This is the recommendation.**

### 4.4 The lower-friction alternative

Drop the `userId` field from the event, and keep only app-level and element-level totals — no
level 2 and no level 3 by user. Aggregate counts, with no identifier, fall inside the
statistics exemption of section 2.1 and need no consent banner. **Cost to the owner: the
monitor loses the per-user view, a stated goal of the design (design section 1).** Keep this
as an option for a future anonymous-only app, not as the default.

## 5. The consent method

- **Place.** A first-run panel in the app, shown once per user, separate from any other
  cookie banner.
- **Text.** Plain words, for example: "We record your clicks in this app to improve it. We
  store a session id and your account id, never your name or your IP address, for up to 395
  days. Allow this?" Two buttons of equal size: "Allow" and "Refuse".
- **Default.** Off. No pre-ticked box. The tracker never calls `start()` before a choice.
- **Equal ease of refusal.** "Refuse" sits next to "Allow", same size, same number of clicks.
  No dark pattern: no hidden link, no pre-selected "Allow", no forced scroll, no repeated nag
  after a refusal. [VERIFIED, EDPB 05/2020 bans a cookie wall and an unequal refusal path,
  read 2026-09-21]
- **Withdrawal.** A link in the app's settings page, "Turn off click tracking", at any time,
  as easy as the first choice. [VERIFIED, GDPR Article 7(3), read 2026-09-21]
- **Consent record.** The app stores, next to the user id: the time of the choice, the
  version of the notice text, and the choice value.
- **The `start()`/`stop()` link.** "Allow" calls `start()`. "Refuse", or the withdrawal link,
  calls `stop()`, which empties the queue and clears the `sessionStorage` key (rule D24). A
  later "Allow" calls `start()` again.

## 6. Draft privacy notice text (issue #42 approves it)

> **Click tracking in this app**
> We record your clicks so we can improve this app. Each record holds the time, the name of
> the button or link, a random session id for your browser tab, and your account id. It never
> holds your name, your email address, or your IP address.
> We keep a record for 30 days in this app's database, and for up to 395 days in the owner's
> local product monitor. The monitor shows totals for the whole app, for each account, and
> for each button or link.
> We use MongoDB Atlas to store the record, Fly.io to run this app, and Cloudflare to serve
> its pages. We do not sell your data, and we do not give it to an advertiser.
> You may allow or refuse this recording at any time in Settings. You may ask us to show,
> correct, or delete your record. Contact: `<owner contact address>`.

## 7. The records

**Article 30 record of processing:**

| Field | Value |
|---|---|
| Purpose | Product-improvement click analytics, per signed-in user |
| Data subjects | Signed-in users of the owner's web apps |
| Data categories | Timestamp, element name, session id, internal user id |
| Legal basis | Consent (GDPR Art. 6(1)(a); Ukraine Art. 11(1)) |
| Recipients | None outside the processors below |
| Processors | MongoDB Atlas, Fly.io, Cloudflare |
| Retention | 30 days (app database), up to 395 days (monitor) |
| Transfers | See section 2.5 |
| Security | TTL delete; a read-only reader account limited to one collection (O4, C35) |

**DPIA screening: not required.** Reason: the data is not special-category, the scale is one
developer with a pilot app, and consent gates the tracker before any risk arises. [VERIFIED,
GDPR Article 35(1) and 35(3) test, read 2026-09-21] Re-screen once a consumer app passes a
few thousand active users, or a new field is added.

**Retention.** 30 days in the app database, a TTL index (contract rule C8). Up to 395 days by
default in the monitor; issue #60 may change this number.

**Erasure path.** The kit's `EventLogStore.deleteByUserId` (decision D18) deletes an app's own
events for one user id. The monitor has its own delete function for one user id. The owner
runs the app-side delete first, then the monitor-side delete (erasure order, decision D15).

**Transfers.** See the table in section 2.5, one row for each processor.

## 8. What the owner decides

- Use consent, not legitimate interest, as the legal basis for every app. **Recommend: yes.**
- Apply one global consent standard to every user, not a split by country. **Recommend: yes.**
- Gate the tracker's `start()` behind the consent panel of section 5. **Recommend: yes.**
- Check the B2B customer's terms for a product-analytics line, and add one if it is absent.
  **Recommend: yes, before the pilot.**
- Get a paid EU representative under Article 27, once an EU user's tracker starts.
  **Recommend: yes, after the lawyer's check of section 9.**
- Keep the drop-`userId` alternative for a future anonymous-only app. **Recommend: yes, as an
  option, not the default.**
- Approve the draft notice text of section 6 for issue #42, with the real contact address
  filled in. **Recommend: yes.**

## 9. When to ask a lawyer

- Is the processing "occasional" under Article 27(2) for this owner, or not (section 2.3)?
- The Ukrainian transfer-abroad rule for MongoDB Atlas, Fly.io, and Cloudflare, under Law No.
  2297-VI, once the reform (draft law No. 8153) passes.
- If an app gets a UK user: from 5 February 2026, the Data (Use and Access) Act 2025 changed
  the PECR analytics exception, but a per-user id may still sit outside it. A short check
  confirms this before a UK launch. [VERIFIED, the change and its date, read 2026-09-21; NOT
  VERIFIED, how it treats a persistent per-user id]
- Whether the B2B customer's employer-employee relation changes the legal basis, or adds a
  joint-controller question.
- A DPIA re-screen, once a consumer app crosses a large user count.

## 10. Sources

| # | Source | URL | Read | Status |
|---|---|---|---|---|
| 1 | ePrivacy Directive 2002/58/EC, Art. 5(3) | eur-lex.europa.eu/eli/dir/2002/58/oj/eng | 2026-09-21 | VERIFIED |
| 2 | EDPB Guidelines 2/2023, technical scope of Art. 5(3) | edpb.europa.eu (guidelines-22023) | 2026-09-21 | VERIFIED |
| 3 | EDPB Guidelines 05/2020 on consent | edpb.europa.eu (guidelines-052020) | 2026-09-21 | VERIFIED |
| 4 | GDPR Art. 5, 6, 7, 13, 21, 25, 27, 28, 30, 35 | gdpr-info.eu | 2026-09-21 | VERIFIED |
| 5 | EDPB Guidelines 3/2018, territorial scope, Art. 3 | edpb.europa.eu (guidelines-32018) | 2026-09-21 | VERIFIED |
| 6 | EDPB Guidelines 05/2021, Art. 3 / Chapter V interplay | edpb.europa.eu (guidelines-052021) | 2026-09-21 | VERIFIED |
| 7 | CNIL Sheet 16, analytics exemption conditions | cnil.fr/en/sheet-ndeg16 | 2026-09-21 | VERIFIED |
| 8 | ICO, storage and access technologies, exceptions | ico.org.uk (…what-are-the-exceptions) | 2026-09-21 | VERIFIED |
| 9 | UK Data (Use and Access) Act 2025, PECR change | Mayer Brown, Practical Law summaries | 2026-09-21 | VERIFIED (fact and date); NOT VERIFIED (statute text) |
| 10 | Law of Ukraine No. 2297-VI, Art. 11 | zakon.rada.gov.ua/laws/show/en/2297-17 | 2026-09-21 | VERIFIED |
| 11 | Draft law No. 8153, GDPR-alignment reform, status | EBA, Council of Europe opinion, CEE Legal Matters | 2026-09-21 | NOT VERIFIED (current reading stage conflicts across sources) |
| 12 | CCPA/CPRA thresholds | Clym and other compliance guides | 2026-09-21 | NOT VERIFIED against the statute text |
| 13 | COPPA, actual-knowledge standard | ftc.gov business guidance | 2026-09-21 | VERIFIED |
| 14 | MongoDB Data Privacy Framework statement | mongodb.com/legal/data-privacy-framework-statement | 2026-09-21 | VERIFIED |
| 15 | Fly.io privacy policy, SCC clause | fly.io/legal/privacy-policy | 2026-09-21 | VERIFIED |
| 16 | Cloudflare GDPR page and SCC document | cloudflare.com/trust-hub/gdpr | 2026-09-21 | VERIFIED |
| 17 | PostHog docs, cookieless mode | posthog.com/docs/privacy | 2026-09-21 | VERIFIED |
| 18 | Matomo docs and FAQ, consent | matomo.org/faq, developer.matomo.org | 2026-09-21 | VERIFIED |
| 19 | Plausible compliance and data policy | plausible.io/compliance | 2026-09-21 | VERIFIED |
| 20 | Amplitude security and privacy page | amplitude.com/security-and-privacy | 2026-09-21 | VERIFIED |
| 21 | Mixpanel GDPR page, retention default | mixpanel.com/legal/mixpanel-gdpr | 2026-09-21 | VERIFIED (page); NOT VERIFIED (exact retention figure, a secondary mention) |
| 22 | EU Art. 27 representative, indicative price | provider marketing pages (DataRep, EU Business Partners) | 2026-09-21 | NOT VERIFIED (not an official price source) |
| 23 | Octometer design document, sections 1, 2.2, 4.1, 4.2, 5, 8 | `docs/superpowers/specs/2026-09-21-octometer-design.md` | 2026-09-21 | VERIFIED |
| 24 | Octometer event log contract v1 | `contract/README.md` | 2026-09-21 | VERIFIED |
