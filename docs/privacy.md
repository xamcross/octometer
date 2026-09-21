# Octometer privacy proposal: legal basis and consent for click tracking

Status: PROPOSAL of 2026-09-21, revised after the second round of review. The owner decides
each item in section 10.

This document is not legal advice. It is a technical proposal for the owner to approve or
reject. Get a lawyer's check for each point in section 10, "When to ask a lawyer".

## 1. The decision in one page

**Recommendation: Mode A. Ask for consent before the tracker starts. Record nothing for a
user who refuses.** Mode A answers the Article 5(3) [the ePrivacy device-storage rule]
question with consent. For a user who refuses, the tracker stores nothing and sends nothing,
so Article 5(3) does not reach that user. Mode C aims to remove the question by design. Section
2.1 shows that it likely fails, because the app still gains access to its own login cookie for a
second purpose. Mode A needs no contract change [no
change to the event-log rules, contract C1 to C37] and no balancing test [the Article 6(1)(f)
weighing step, section 2.2].

Matomo's `requireConsent()` matches Mode A: no tracking request and no cookie until consent.
PostHog's option `cookieless_mode: "on_reject"` does not match Mode A. Its docs read: "If consent
is denied, still counts those users, using a privacy-preserving hash calculated on PostHog's
servers." That is a fifth behaviour, and it is not Mode C, because the hash does not come from a
login-session cookie. This proposal does not use it. PostHog's own recommended consent pattern —
opted out by default, then `opt_in_capturing()` — does match Mode A. [VERIFIED, PostHog docs,
read 2026-09-21]

EDPB Guidelines 2/2023 paragraph 55 states: "Unless the entity can ensure that the IP address does
not originate from the terminal equipment of a user or subscriber, it has to take all the steps
pursuant to the Article 5(3) ePD." [VERIFIED, EDPB 2/2023 paragraph 55, read 2026-09-21] A server
that derives a hash from such an IP address therefore stays inside the Article 5(3) question.
[INFERENCE]

**Cost to the owner, before the pilot.** Eight items of section 9: a consent store, a consent
API path in the contract, an Angular panel, a Settings toggle, the `start()`/`stop()` gate, a
withdrawal path, a notice page, and a short DPIA, due before the pilot. The event-log contract
(rules C1 to C37) does not change. The contract gains one new consent path.

**Recurring cost.**
- An EU representative service, once Article 27 applies (section 2.3). Get a quotation. No
  public price source exists.
- The owner's own manual delete in the monitor, for each withdrawal, inside 7 days
  (section 6).
- A re-check of the processor section every six months (section 5).

**What the owner does next.**
- Approve Mode A below.
- Approve the app table.
- Start the `investguideua` pilot work of section 9.

**The expected rate.** Plausible's own calculator page gives an EU/EEA acceptance-rate estimate
of 40 to 50 percent for an equal-choice panel, and 50 to 60 percent for a mixed global audience.
The page calls these numbers "reasonable starting points based on published research", not a
guarantee. [VERIFIED, plausible.io/cookie-banner-traffic-loss-calculator, read 2026-09-21; a
vendor source, since Plausible sells a cookieless product] A study looked at pop-ups on the top
10,000 UK sites: 680 pop-ups, from the five leading consent tools. It found that removing an
equal "reject" button from the first page raises the accept rate by 22 to 23 percentage points.
[VERIFIED, Nouwens et al., CHI 2020, arxiv.org/abs/2001.02479, read 2026-09-21] **Effect on the
monitor: expect the level 1 totals to show under half of real clicks once consent is equal and
honest.** [INFERENCE] A count from one app is not comparable with a count from another app at a
different rate. The two totals never equal the app's real traffic.

### The apps

| App | Kind | Stack | Rollout order |
|---|---|---|---|
| `investguideua` | Consumer, signed-in users | Spring Boot, Angular | 1, the pilot |
| `traficio` | Consumer, signed-in users | Ktor, Angular | 2 |
| `tuliplot` | Consumer, signed-in users | Spring Boot, Angular | 3 |
| `cadence` | B2B, employees of business customers | Spring Boot, Angular, Cloudflare Pages | 4, last |

The `investguideua` row rests on the owner's own statement (2026-09-21); the design document
names only three apps (design section 8). The owner has since added `investguideua` as the
pilot. Users of all four apps are in Ukraine, in the EU, and in the USA (owner statement,
2026-09-21).

### The four modes, judged honestly

| Mode | What it does | Needs a legal check |
|---|---|---|
| A. Full consent (recommended) | Consent gate before `start()`. Nothing recorded on refusal. | Yes — Article 27 [an EU representative duty] still applies once an EU user is in scope (section 2.3). |
| B. The middle way | Consent for the user level. A refusal still writes the event with `userId = null`. | Yes — Article 5(3) and a contract question stay open (below). |
| C. Server-derived session id | The app derives `sessionId` from its own login-session cookie. The tracker writes nothing to `sessionStorage`. | Yes — Article 5(3) likely still applies (below), and it needs a new contract version. |
| D. No user id at all | The event drops `userId`. Only app-level and element-level totals remain. | Yes — no EU-wide statistics exemption exists (below). |

**Mode B detail.** The `sessionStorage` write and the send of the click still trigger Article
5(3), even with no `userId` (section 2.1). Contract rule C6 gives `null` the meaning "not
signed in"; a refusing signed-in user needs a new value, or the anonymous bucket mixes two
different populations.

**Mode C detail.** Article 5(3) likely still applies. The app gains access to its own login
cookie for a second purpose, and the ePrivacy exemption is limited to the sole purpose of the
requested service (section 2.1). This is a lawyer question (section 10), not a settled result,
and it is why Mode C stays a later goal, not the MVP recommendation. It also needs a new
event-log contract version, because the tracker stops creating its own `sessionId` (rule C5),
and the server assigns it instead (rule C10, a major contract change).

**Mode D detail.** The first version of this document called Mode D free of consent. That is
false. No EU-wide statistics exemption exists. CNIL's rests on Article 82 of the French Data
Protection Act [France's cookie law]. Germany's TDDDG section 25 [Germany's cookie law] has
only two narrow exceptions, neither for statistics. [VERIFIED, gesetze-im-internet.de/ttdsg/
__25.html, read 2026-09-21] The `sessionStorage` write and the send of the click still happen,
so Article 5(3) still applies in Germany and in any state with no exemption.

Mode A narrows the Article 5(3) question to nothing for a refusing user. No mode clearly removes
the question by design. Mode C aims to, and section 2.1 leaves the point open. [INFERENCE]

## 2. The legal frame, corrected

### 2.1 Access to the device (ePrivacy Article 5(3))

The consolidated ePrivacy Directive (as amended by Directive 2009/136/EC) needs consent for a
storage or an access on a user's device. [VERIFIED, eur-lex.europa.eu, CELEX
02002L0058-20091219, read 2026-09-21] The original 2002 text asked only for a right to refuse;
the consent wording is newer.

EDPB Guidelines 2/2023 name **three** criteria, not four: (A) "information"; (B) "terminal
equipment of a subscriber or user", which needs a "public communications network"; (C)
"storage" or "gaining of access". [VERIFIED, EDPB 2/2023 paragraph 6, read 2026-09-21] Storage
and access do not need to come from the same party (paragraph 31). Storage has no limit on
lifetime or medium (paragraphs 37-38). Sending stored information back over the network through
client-side code is itself "a gaining of access" (paragraphs 53 and 63). The tracker's
`sessionStorage` write, and its send of the session id, both meet this test. [INFERENCE]

**The exemptions are narrower than the first draft said, and they are national, not EU-wide.**
CNIL's statistics exemption has limits: a 13-month tracker lifetime, a 25-month cap on the
collected data, no cross-match with other processing, and one publisher only. It also keeps the
user's objection path. It is an opt-out regime, not "no consent needed". [VERIFIED, CNIL Sheet
16 and the CNIL page of 4 July 2025, read 2026-09-21] The ICO's exception is a purpose test —
"the sole purpose of the storage or access is to enable the person... to collect information for
statistical purposes about how the service is used... with a view to making improvements to
the service" — and the same page states that a visitor id connected to site activity still
needs consent. [VERIFIED, ico.org.uk, storage and access technologies, "what are the
exceptions", read 2026-09-21] Germany's TDDDG section 25 has no statistics exemption at all.
**Octometer keeps a user id at user level, so no version of the CNIL or ICO exemption applies,
and Germany has none to apply.** [INFERENCE]

**Mode C does not clearly avoid Article 5(3).** The tracker itself stores and reads nothing on
the device, but the app gains access to its own login cookie for a second, analytics purpose.
The exemption in the ePrivacy Directive text applies "for the sole purpose of carrying out the
transmission... or as strictly necessary" for the requested service — a limit the first draft
never quoted. A second purpose likely breaks it. Paragraph 56 [EDPB 2/2023] covers only an
access method that avoids consent by design; it does not remove a purpose-limit problem. **This
stays a lawyer question (section 10), not a settled result.** [INFERENCE]

Ukraine has no direct counterpart of the ePrivacy Directive. Its electronic-communications law
(Law No. 1089-IX, adopted 16 December 2020, in force 1 January 2022) follows the European
Electronic Communications Code, not the ePrivacy Directive. Chapter XV holds Articles 119 to
121 only; Article 120 is a spam rule, the Ukrainian parallel of ePrivacy Article 13. It has no
Article 5(3) equivalent. [VERIFIED, Law No. 1089-IX, second regulator review, 2026-09-21]

### 2.2 The legal basis (GDPR Article 6, Ukraine Article 11)

Article 5(3) [the ePrivacy consent rule] and Article 6 [the GDPR legal-basis rule] are two
separate steps, not one. EDPB Opinion 5/2019 states: "Where these articles require consent for
the specific actions they describe, the controller cannot rely on the full range of possible
lawful grounds provided by article 6 of the GDPR." [VERIFIED, EDPB Opinion 5/2019 paragraph 40,
read 2026-09-21] It adds: "Subsequent processing of personal data including personal data
obtained by cookies must also have a legal basis under article 6 of the GDPR in order to be
lawful." [VERIFIED, EDPB Opinion 5/2019 paragraph 41, read 2026-09-21]

Article 5(3) governs the `sessionStorage` write, and needs consent. The later storage and
analysis of the click history needs its own Article 6 basis. This document recommends consent
under Article 6(1)(a) for both steps, so the two stay aligned. [INFERENCE] Article 6(1)(f) [the
legitimate-interest ground] would need a balancing test instead; this proposal does not rely on
it.

### 2.3 Does the GDPR apply to this owner?

- **Article 3(2)(a)** [the targeted-offer rule], offering a service to EU users, needs a
  targeting test: a named Member State, a non-local language, currency, or top-level domain.
  [VERIFIED, EDPB 3/2018 pages 17-18] Run this test for each app before the pilot.
- **Article 3(2)(b)** [the monitoring rule], monitoring behaviour, lists cookie and other
  tracking techniques as an indicator, but "the EDPB does not consider that any online
  collection or analysis of personal data of individuals in the EU would automatically count as
  'monitoring'". [VERIFIED, EDPB 3/2018 page 20] The purpose test decides it: the processing
  must aim to profile a person, in particular to analyse or predict personal preferences,
  behaviours, or attitudes. Octometer's per-user click history over time meets this test, so
  the GDPR applies to every EU user on this route. [INFERENCE]
- **Article 27(1)** [the EU-representative duty] needs a representative "designated in
  writing", "where Article 3(2) applies" — not once a risk appears. [VERIFIED,
  eur-lex.europa.eu] The Article 27(2)(a) exemption applies to processing which is "occasional",
  "does not include, on a large scale, processing of special categories of data as referred to
  in Article 9(1) or processing of personal data relating to criminal convictions and offences
  referred to in Article 10" and "is unlikely to result in a risk to the rights and freedoms of
  natural persons, taking into account the nature, context, scope and purposes of the
  processing" — not "a high risk".
  [VERIFIED, eur-lex.europa.eu, GDPR Art. 27(2)(a); EDPB 3/2018 page 26] Article 27(3) adds:
  "The representative shall be established in one of the Member States where the data
  subjects... are." [VERIFIED, eur-lex.europa.eu, GDPR Art. 27(3)] "Occasional" means not
  regular and outside the regular course of business (EDPB 3/2018 page 25). Continuous click
  tracking is neither occasional nor clearly low-risk. **The owner needs an EU representative
  from the first day an EU user's tracker runs.** [INFERENCE] This duty does not depend on the
  tracker. If the Article 3(2)(a) test above already applies to an existing app, the duty
  exists today, with no tracker at all. This needs a lawyer's check (section 10).
- **Chapter V** [the GDPR's rules on data transfers outside the EU] does not reach the first
  step, an EU user's click that arrives at the owner's own app, because the owner passes it to
  nobody at that point. It does reach the next step. The owner then passes the data to three
  processors outside the EEA. EDPB 05/2021, Example 2, page 9, calls that disclosure a transfer,
  and it requires Article 28 and Chapter V. [VERIFIED, EDPB 05/2021, Example 2, page 9] Section 5
  holds the open question. A cookie disclosure at the first step is a transmission by the
  website operator, not by the data subject (footnote 15, page 9). [VERIFIED, EDPB 05/2021,
  footnote 15, page 9] The owner must still weigh the third country's legal framework and tell
  the user their data leaves the EU, even with no formal transfer. [VERIFIED, EDPB 05/2021,
  page 16]
- **Article 8** [the child's-consent rule] applies because the basis is consent. It covers a
  service "offered directly to a child": valid at 16, or lower if a Member State sets it, never
  below 13; below that, consent needs a parental-responsibility holder, with "reasonable
  efforts to verify" it. [VERIFIED, eur-lex.europa.eu, Art. 8(1)-(2)] No app screens for a
  child user today. Add this to section 10.
- **The B2B app (`cadence`).** The owner decides the purpose, so Article 28(10) makes him a
  controller for that purpose, not a processor. [VERIFIED, eur-lex.europa.eu, GDPR Art. 28(10)]
  Article 28(10) does not use the word "independent"; whether this creates a joint-controller
  relation with the business customer stays an open question (section 10). He needs the
  customer's permission in the contract before he may process for his own purpose at all
  (Article 28(3)(a)) — a precondition, not optional support text. Employee consent stays valid
  because the employer cannot read the result: the monitor binds to `127.0.0.1` with no login
  (D12), so no named employee's clicks reach the employer. Free consent still needs "no adverse
  consequences at all" (EDPB 05/2020 paragraph 22), against a known power imbalance (paragraph
  24).

## 3. Ukraine

The transfer question has an answer today. Article 29(3) treats a transfer as safe with no
extra tool to a state whose capital-markets regulator signed the IOSCO Multilateral MoU; the US
SEC and CFTC both did, in 2002. [VERIFIED, iosco.org signatories list, read 2026-09-21] So a
transfer to MongoDB Atlas, Fly.io, or Cloudflare needs no separate Ukrainian tool. [INFERENCE]
Consent stays the safer legal ground in Ukraine too, for the same profiling reason as section
2.2. Detail: Appendix A.1.

## 4. The USA

No US federal or state law applies to this owner today: CCPA/CPRA needs a revenue, buy/sell
volume, or resale-share test that one developer meets on none, and Texas and Nebraska exempt a
small business on a test one developer also meets. Ask a lawyer before a US consumer launch.
Detail: Appendix A.2.

## 5. The processors

| Processor | Its own stated transfer tool | Status |
|---|---|---|
| MongoDB Atlas | EU-US Data Privacy Framework, self-certified, for data transferred from the EEA, the UK, or Switzerland; an SCC is available by agreement | VERIFIED, mongodb.com/legal/data-privacy-framework-statement, read 2026-09-21 |
| Fly.io | EU-US Data Privacy Framework (plus the UK and Swiss extensions); its privacy policy names an SCC one time, for Fly.io's own vendors and affiliates, not its customers | VERIFIED, fly.io/legal/privacy-policy, read 2026-09-21. `fly.io/legal/dpa` returns HTTP 404 |
| Cloudflare | Standard Contractual Clauses for a "Restricted Transfer"; a DPF transfer to the US is defined as not a Restricted Transfer | VERIFIED, DPA version 6.4, effective 3 April 2026, cloudflare.com/cloudflare-customer-dpa, read 2026-09-21. Cloudflare, Inc.'s DPF status reads "Active - Re-certification under Review" for each of its three certifications |

**The vendor tools are origin-based, not exporter-based.** MongoDB's DPF statement covers data
transferred to it from the EEA, the UK, or Switzerland, except where any of our agreements with
those businesses stipulate a different transfer mechanism recognized by the relevant authority
(e.g. standard contractual clauses). [VERIFIED, mongodb.com/legal/data-privacy-framework-statement,
read 2026-09-21] Cloudflare's DPA
defines a "Restricted Transfer" by the same origin test, and states that a DPF transfer to the
US is not a Restricted Transfer. [VERIFIED, Cloudflare DPA version 6.4, effective 3 April 2026,
read 2026-09-21] An EU user's click is EEA-origin data, so both tools may reach this flow.
**Whether a controller established outside the EEA, caught by Article 3(2), may rely on them
stays open.** This is a lawyer question (section 10), not a settled finding.

**The Data Privacy Framework carries an open risk.** The Commission's adequacy decision stands.
The EU General Court dismissed an annulment action (Latombe, T-553/23) on 3 September 2025, and
an appeal is now pending (C-703/25 P, lodged 31 October 2025). [VERIFIED, CELEX 62025CN0703, OJ
C/2025/6610, and CELEX 62023TJ0553, read 2026-09-21] Treat the DPF as active but under appeal,
and re-check this section every six months.

## 6. The consent method

- **Place.** A panel in the app, at the user's next sign-in, apart from any other cookie
  banner. It returns at each later sign-in, until the user answers, or until the count of the
  bullet "No answer yet" stops it.
- **Panel text (draft, for issue #42 to approve).**
  > We would like to record which buttons and links you use, so we can improve this app.
  > We do not record your name. We do not store your IP address.
  > You may allow or refuse this now, and you may withdraw your choice at any time in Settings.
  > Read the full notice: `<link>`.
  >
  > [ Refuse ] [ Allow ]
  > [ x ] Close. This makes no choice. We ask you again at your next sign-in.
- **Buttons.** "Allow" and "Refuse", equal size and an equal number of clicks. If one button is
  highlighted, it must be "Refuse". EDPB 03/2022 states that the options need not look
  identical, but if one is highlighted, "this needs to be the most restrictive one regarding
  personal data". [VERIFIED, EDPB 03/2022 v2.0, page 23, read 2026-09-21] The EDPB Cookie
  Banner Taskforce report adds that "a general banner standard concerning colour and/or
  contrast cannot be imposed on data controllers". [VERIFIED, EDPB Cookie Banner Taskforce
  report, paragraph 17, adopted 17 January 2023, read 2026-09-21]
- **Default.** Off. No pre-ticked box. `start()` never runs before a choice (rule D24).
- **On a read failure.** If the app cannot read the stored consent state at start, it treats
  the user as `unknown` and keeps the tracker off.
- **No answer yet.** The app appends an `unknown` document each time it shows the panel. A
  close makes no choice, and it is not a consent and not a refusal. The panel returns at the
  next sign-in, up to three `unknown` documents for the current notice version. After the
  third one the app stops asking, and it keeps the tracker off. The Settings toggle stays
  open to the user.
- **A text change.** A new notice version asks again, and the count starts at zero. The old
  consent document stays, next to the new one (append-only, section 8).
- **Withdrawal.** A two-way toggle in Settings, showing the current state, as easy to reach as
  the first choice. [VERIFIED, GDPR Article 7(3), read 2026-09-21] The monitor binds to
  `127.0.0.1` and has no login (decision D12), so the app cannot call it. **MVP proposal:** a
  withdrawal calls `stop()` and `EventLogStore.deleteByUserId` (decision D18) for the app's
  own store at once. It appends a `withdrawn` document to `octometer_consent`. The owner then
  runs the monitor's own delete for the same user id by hand:
  `DELETE /api/apps/{appId}/events?userId=<id>` (design D13). The owner waits one poll cycle
  after the app delete (decision D15's order: app, then one poll cycle, then monitor). A poll
  in flight can otherwise copy the deleted rows back. The owner completes the monitor delete
  within 7 days [proposed; the outer limit is one month under GDPR Article 12(3)]. [VERIFIED,
  eur-lex.europa.eu, GDPR Art. 12(3), read 2026-09-21] The owner then appends a
  `monitor_erased` document. Section 9 adds this owner routine. Section 9 adds a later item
  for the monitor to read the withdrawal list itself.
- **API path.** The contract has no consent endpoint today. Propose
  `/api/octometer/v1/consent`, added to the contract before the section 9 kit work starts.
- **The record.** Collection `octometer_consent`, in the database of the app, next to
  `octometer_events` (contract C1), separate from the event log. One append-only document for
  each choice or dismissal:

  | Field | Type | Value |
  |---|---|---|
  | `userId` | string | the internal user id (design decision O5) |
  | `appId` | string | the app name |
  | `ts` | Date | the time of the choice or dismissal |
  | `noticeVersion` | string | the version id of the shown notice text |
  | `state` | string | one of `unknown`, `allowed`, `refused`, `withdrawn`, `monitor_erased` |

  A dismissal with no choice also appends a document, with `state: unknown`. A withdrawal
  appends a document with `state: withdrawn`. The owner's routine reads each `withdrawn`
  document that has no later `monitor_erased` document for the same `userId`. The owner appends
  a `monitor_erased` document after the monitor delete runs. The notice text itself is stored
  once for each notice version, in a small collection named `octometer_consent_notice`, keyed
  by `noticeVersion`; a consent document keeps only the version id, not a copy of the text. No
  IP address, no device fingerprint. [VERIFIED, EDPB 05/2020 paragraph 106, read 2026-09-21]
  GDPR Article 7(1) [the duty to show that consent was given] and Ombudsman Order No. 1/02-14
  clause 2.8 both require the owner to keep this evidence for as long as the processing runs.
  [VERIFIED, eur-lex.europa.eu, GDPR Art. 7(1); Order No. 1/02-14 clause 2.8, second regulator
  review, 2026-09-21] The TTL delete of section 8 does not reach `octometer_consent`. An
  Article 17 erasure request leaves this consent evidence in place. [VERIFIED, GDPR Art.
  17(3)(e); EDPB 05/2020 paragraph 107, read 2026-09-21]

## 7. Draft privacy notice text (issue #42 approves it)

[Add the EU representative once section 2.3 confirms the duty.]

> **Click tracking in this app**
> The controller is `<owner name, contact address>`, established in Ukraine.
> We record your clicks so we can improve this app. Each record holds the time, the name of
> the button or link, a random session id for your browser tab, and your account id. It never
> holds your name, your email address, or your IP address, except that a request with no
> signed-in user may use your IP address for one minute, in memory only, to limit abuse.
> Legal basis: your consent (GDPR Article 6(1)(a); Ukraine Article 11(1)).
> Recording is voluntary. A refusal changes nothing else in the app. If you gave consent and
> later withdraw it, we stop new recording. This does not affect the lawfulness of the
> recording we did before you withdrew.
> We keep your record for a set period, stated at `<link to the current retention decision>`,
> and for a separate period, also stated there, in the owner's own local product monitor — a
> computer that the developer controls.
> We use MongoDB Atlas to store the record, Fly.io to run this app, and Cloudflare to serve its
> pages, each outside the EU. The EU-US Data Privacy Framework adequacy decision covers these
> three companies in the USA. No EU adequacy decision covers Ukraine. For Ukraine we use the
> safeguards named at `<link to the safeguards list>`, and you may ask us for a copy of them.
> We do not sell your data, and we do not give it to an advertiser.
> You may allow or refuse this recording at any time in Settings, and you may withdraw your
> consent there at any time. You may ask us to show, correct, delete, or export your record, or
> to pause its use. You may complain to your national data protection authority. Contact:
> `<owner contact address>`.

## 8. The records

**Article 30 record of processing:**

| Field | Value |
|---|---|
| Controller | `<owner name>`, plus the EU representative once appointed |
| Purpose | Product-improvement click analytics, per signed-in user |
| Data subjects | Signed-in users of the owner's web apps |
| Data categories | Timestamp, element name, session id, internal user id; an IP address in memory only, for a request with no user id (rate limit, rule D20) |
| Legal basis | Consent (GDPR Art. 6(1)(a); Ukraine Art. 11(1)) |
| Recipients | None outside the processors below |
| Processors | MongoDB Atlas, Fly.io, Cloudflare |
| Retention | Set by issue #60; not yet decided |
| Transfers | See section 5 |
| Security | TTL delete; a read-only reader account limited to one collection (O4, C35); the Atlas access list is `0.0.0.0/0` with no audit; the monitor has no login (D12) |

**DPIA screening, the nine WP248 criteria:**

| # | WP248 criterion | Met here? |
|---|---|---|
| 1 | Evaluation or scoring, including profiling | Yes — a per-user click history builds a behavioural profile. |
| 2 | Automated decision-making with a legal or similar effect | No. |
| 3 | Systematic monitoring | Yes — continuous tracking of a signed-in user's clicks. |
| 4 | Sensitive data or data of a highly personal nature | No — though a per-user click history in `investguideua` may show a financial interest (recital 71's "economic situation"). |
| 5 | Data processed on a large scale | Not at pilot scale; yes once all four apps run. |
| 6 | Matching or combining datasets | No. |
| 7 | Data concerning vulnerable data subjects | Yes for `cadence` — an employee, under an employer relation. |
| 8 | Innovative use or a new technological solution | No. |
| 9 | The processing prevents a right, a service, or a contract | No. |

WP248 rev.01, page 11, states: "In most cases, a data controller can consider that a
processing meeting two criteria would require a DPIA to be carried out." [VERIFIED, WP248
rev.01, page 11, read 2026-09-21]
Criteria 1 and 3 already hold for the `investguideua` pilot; `cadence` adds criterion 7.
**A short DPIA is due before the `investguideua` pilot starts, and it covers all four apps.**
[INFERENCE] Consent makes the processing lawful; it does not by itself lower the risk that a
DPIA screens for. Section 9 adds this item.

**Erasure path.** `EventLogStore.deleteByUserId` (D18) deletes the app's own events at once.
The monitor's own delete is a manual step the owner runs, after one poll cycle (decision D15;
section 6). A withdrawal (section 6) and a data-subject erasure request both use this path.

## 9. The cost for an MVP

**Cannot wait, `investguideua` only:**
- Consent store and read/write API, built in the `investguideua` app for the pilot — Medium. A
  shared kit-level module comes later, once the pilot proves the shape.
- A consent API path added to the contract, for example `/api/octometer/v1/consent` — Small.
- Angular consent panel, with the text of section 6 — Medium.
- Settings toggle, a two-way state control — Small.
- The `start()`/`stop()` calls into the consent state — Small. The tracker itself (decision
  D24) needs no change.
- Withdrawal path: an app-side delete at once, a `withdrawn` document in `octometer_consent`,
  and an owner routine to run the monitor's own delete within 7 days, then append
  `monitor_erased` — Medium.
- Notice page, with a version id and a stored copy of the shown text for that version — Small.
- A short DPIA, run once before the pilot starts, covering all four apps (section 8) — Small.

**Cannot wait, independent of the tracker:**
- Appoint an EU representative under Article 27, once the Article 3(2)(a) test confirms the
  duty for an existing app (section 2.3). Get a quotation for the recurring cost — Small, an
  owner task, not code.

**Can wait until after the `investguideua` pilot:**
- Move the consent store into a kit module — Medium.
- Connect each other app to the kit module, for `traficio`, `tuliplot`, and `cadence` — Small,
  each. Issue #71 covers the Maven distribution route for the kit.
- The monitor's own consent-rate view, per app — Medium.
- Rollout of each other app's consent panel — Medium, each.
- A DPIA re-screen, before the second and later app launches — Small.
- The B2B contract-terms check for `cadence` (section 2.3) — Small.
- The monitor reads the `withdrawn` documents itself, from `octometer_consent`. This needs a
  second `find` database privilege and a design decision — Medium.

## 10. What the owner decides

- Use Mode A (full consent), not Mode B, C, or D, as the MVP method. **Recommend: yes.**
- Keep one global consent standard for every app and every user. **Recommend: yes.**
- Check `cadence`'s customer contracts for a product-analytics permission line, before the
  B2B rollout. **Recommend: yes.**
- Get a paid EU representative under Article 27, now if an existing app already reaches an EU
  user, or before any app adds the tracker for an EU user, whichever is sooner. **Recommend:
  yes, after the lawyer's check below.** Budget: get a quotation; no public price source exists.
- Add an age question or an age gate to a consumer app, for Article 8. **Recommend: yes, before
  a consumer app is known to reach a user under 16.**
- Approve the draft notice text of section 7 for issue #42, once the retention placeholder is
  filled by issue #60. **Recommend: yes.**
- Keep Mode C (server-derived session id) as a later goal, not the MVP. **Recommend: yes.**

**When to ask a lawyer:**
- Is the processing "occasional" under Article 27(2) for this owner? (Section 2.3.)
- Does the owner need his own Article 46 SCC as exporter, given the vendor tools are
  origin-based, not exporter-based? (Section 5.)
- Does Mode C's access to the login cookie, for a second analytics purpose, survive the
  sole-purpose limit of Article 5(3)? If not, does it need an Article 6(1)(f) balancing test?
  (Section 2.1.)
- Does the B2B employer-employee relation create a joint-controller question for `cadence`?
  (Section 2.3.)

## 11. Sources

| # | Source | URL | Read | Status |
|---|---|---|---|---|
| 1 | ePrivacy Directive, consolidated Art. 5(3) | eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:02002L0058-20091219 | 2026-09-21 | VERIFIED (EUR-Lex returns HTTP 202 to a plain fetch, its own bot check; the text was read directly) |
| 2 | EDPB Guidelines 2/2023, technical scope Art. 5(3) | edpb.europa.eu/system/files/2024-10/edpb_guidelines_202302_technical_scope_art_53_eprivacydirective_v2_en_0.pdf | 2026-09-21 | VERIFIED, HTTP 200 |
| 3 | EDPB Guidelines 05/2020 on consent | edpb.europa.eu/system/files/documents/files/file1/edpb_guidelines_202005_consent_en.pdf | 2026-09-21 | VERIFIED, HTTP 200 |
| 4 | GDPR (OJ L 119, 4.5.2016) | eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679 | 2026-09-21 | VERIFIED, HTTP 202 (EUR-Lex bot check) |
| 5 | EDPB Guidelines 3/2018, territorial scope | edpb.europa.eu/system/files/documents/files/file1/edpb_guidelines_3_2018_territorial_scope_after_public_consultation_en_1.pdf | 2026-09-21 | VERIFIED, HTTP 200 |
| 6 | EDPB Guidelines 05/2021, Art. 3 / Chapter V | edpb.europa.eu/system/files/documents/2023-02/edpb_guidelines_05-2021_interplay_between_the_application_of_art3-chapter_v_of_the_gdpr_v2_en_0.pdf | 2026-09-21 | VERIFIED, HTTP 200. The document has no numbered paragraphs; cite by heading or page, not "paragraph". |
| 7 | EDPB Guidelines 03/2022, deceptive design patterns | edpb.europa.eu/system/files/2023-02/edpb_03-2022_guidelines_on_deceptive_design_patterns_in_social_media_platform_interfaces_v2_en_0.pdf | 2026-09-21 | VERIFIED, HTTP 200 |
| 8 | EDPB Cookie Banner Taskforce report, adopted 17 Jan 2023 | edpb.europa.eu/system/files/2023-01/edpb_20230118_report_cookie_banner_taskforce_en.pdf | 2026-09-21 | VERIFIED, HTTP 200. The filename date (18 Jan) is the file date, not the adoption date. |
| 9 | CNIL sheet on analytics tools and the CNIL page of 4 July 2025 | cnil.fr/en/sheet-ndeg16-use-analytics-your-websites-and-applications ; cnil.fr/fr/cookies-solutions-pour-les-outils-de-mesure-daudience | 2026-09-21 | VERIFIED, both HTTP 200 |
| 10 | ICO, storage and access technologies, exceptions | ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-exceptions/ | 2026-09-21 | VERIFIED, HTTP 200 |
| 11 | Germany TDDDG section 25 | gesetze-im-internet.de/ttdsg/__25.html | 2026-09-21 | VERIFIED, HTTP 200 |
| 12 | Law of Ukraine No. 2297-VI, Art. 2, 8(2)(8), 9, 11, 12, 21, 22, 29(3) | zakon.rada.gov.ua/laws/show/2297-17 (Ukrainian text, includes the 2024 IOSCO amendment) | 2026-09-21 | VERIFIED, HTTP 200 (needs an insecure TLS retry from this client; the Ukrainian-government TLS stack does not complete a plain handshake) |
| 13 | Law No. 383-VII (2013 amendment to Art. 11) | zakon.rada.gov.ua/laws/show/383-18 | 2026-09-21 | VERIFIED, HTTP 200 |
| 14 | Law No. 3585-IX (22 Feb 2024) and the IOSCO MMoU signatory list | iosco.org/about/?subsection=mmou&subsection1=signatories | 2026-09-21 | VERIFIED, HTTP 200 |
| 15 | Cabinet Resolution No. 910 (16 Aug 2022) | zakon.rada.gov.ua/laws/show/910-2022-п | 2026-09-21 | PARTIALLY VERIFIED (paraphrase, not raw text); could not connect from this session (connection timeout) this round; content per second regulator review, 2026-09-21 — NOT independently re-verified this round |
| 16 | Draft law No. 8153, status, Resolutions 4065-IX and 4729-IX | itd.rada.gov.ua/billInfo/Bills/Card/40707 ; zakon.rada.gov.ua/laws/show/4065-20 ; /4729-20 | 2026-09-21 | VERIFIED, all HTTP 200 |
| 17 | Ombudsman Order No. 1/02-14, clauses 1.2 and 2.8 | zakon.rada.gov.ua/rada/show/v1_02715-14 | 2026-09-21 | VERIFIED (clauses 1.2 and 2.8), second regulator review, 2026-09-21; HTTP 200 |
| 18 | Law No. 1089-IX, On Electronic Communications | zakon.rada.gov.ua/laws/show/1089-20 | 2026-09-21 | VERIFIED (adoption date, in-force date, Chapter XV scope), second regulator review, 2026-09-21; HTTP 200 |
| 19 | Cal. Civ. Code 1798.140(d)(1), CCPA/CPRA threshold | leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=CIV&sectionNum=1798.140 | 2026-09-21 | VERIFIED, HTTP 200 |
| 20 | CPPA, CPI-adjusted threshold figure (USD 26,625,000) | cppa.ca.gov/regulations/cpi_adjustment.html | 2026-09-21 | VERIFIED, HTTP 200 |
| 21 | Texas Business & Commerce Code Ch. 541, and Nebraska Data Privacy Act | statutes.capitol.texas.gov/Docs/BC/htm/BC.541.htm ; nebraskalegislature.gov/laws/display_html.php?begin_section=87-1101&end_section=87-1130 | 2026-09-21 | Texas: VERIFIED, HTTP 200. Nebraska: could not connect from this session (connection timeout); content per second regulator review, 2026-09-21 — NOT independently re-verified this round |
| 22 | Connecticut Public Act 25-113 (SB 1295) | wiley.law/alert-Major-Changes-to-Connecticut-Consumer-Privacy-Law-Will-Take-Effect-July-1-2026 | 2026-09-21 | PARTIALLY VERIFIED, HTTP 200 |
| 23 | IAPP US State Privacy Legislation Tracker | iapp.org/resources/article/us-state-privacy-legislation-tracker | 2026-09-21 | VERIFIED, HTTP 200 |
| 24 | COPPA, 16 CFR 312.2-312.3 | ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.3 | 2026-09-21 | VERIFIED, HTTP 200 |
| 25 | COPPA 2025 amendments | ftc.gov/legal-library/browse/rules/childrens-online-privacy-protection-rule-coppa ; federalregister.gov/documents/2025/04/22/2025-05904/childrens-online-privacy-protection-rule | 2026-09-21 | VERIFIED, HTTP 200 (a plain fetch with no browser user-agent redirects to the site's own bot check; a browser-agent fetch and the Federal Register API both confirm document 2025-05904, "Children's Online Privacy Protection Rule", published 22 April 2025, effective 23 June 2025), read 2026-09-21 |
| 26 | MongoDB Data Privacy Framework statement | mongodb.com/legal/data-privacy-framework-statement | 2026-09-21 | VERIFIED, HTTP 200 |
| 27 | Fly.io privacy policy; `fly.io/legal/dpa` | fly.io/legal/privacy-policy ; fly.io/legal/dpa | 2026-09-21 | VERIFIED, HTTP 200. `fly.io/legal/dpa` returns HTTP 404 |
| 28 | Cloudflare GDPR page and DPA v6.4 | cloudflare.com/trust-hub/gdpr/ ; cloudflare.com/cloudflare-customer-dpa/ | 2026-09-21 | VERIFIED, both HTTP 200 |
| 29 | DPF adequacy decision and Latombe v Commission, T-553/23 | eucrim.eu/news/general-court-confirms-adequacy-of-us-data-protection/ | 2026-09-21 | VERIFIED, HTTP 200 |
| 30 | PCLOB board composition | pclob.gov/Board/Index | 2026-09-21 | VERIFIED, HTTP 200 — one current member, 17 names under "Former Board Members" |
| 31 | CJEU appeal of Latombe v Commission | eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62025CN0703 (CELEX 62025CN0703, OJ C/2025/6610); the T-553/23 judgment is CELEX 62023TJ0553 | 2026-09-21 | VERIFIED, HTTP 202 (EUR-Lex bot check) |
| 32 | PostHog docs, cookieless mode | posthog.com/docs/privacy/data-collection | 2026-09-21 | VERIFIED, HTTP 200 |
| 33 | Matomo tracking-consent guide and `config_id` FAQ | developer.matomo.org/guides/tracking-consent ; matomo.org/faq/general/how-is-the-visitor-config_id-processed/ | 2026-09-21 | VERIFIED, both HTTP 200 |
| 34 | Matomo's own CNIL self-assessment FAQ | matomo.org/faq/how-to/how-do-i-configure-matomo-without-tracking-consent-for-french-visitors-cnil-exemption/ | 2026-09-21 | VERIFIED, HTTP 200 |
| 35 | Plausible data policy and traffic-loss calculator | plausible.io/data-policy ; plausible.io/cookie-banner-traffic-loss-calculator | 2026-09-21 | VERIFIED, both HTTP 200. The page calls its numbers estimates, not guarantees. |
| 36 | Amplitude browser SDK docs, `setOptOut` | amplitude.com/docs/sdks/analytics/browser/browser-sdk-2 ; amplitude.com/docs/apis/analytics/user-privacy | 2026-09-21 | VERIFIED, both HTTP 200 |
| 37 | Mixpanel JS SDK docs and GDPR retention page | docs.mixpanel.com/docs/tracking-methods/sdks/javascript ; docs.mixpanel.com/docs/privacy/gdpr-compliance | 2026-09-21 | VERIFIED, both HTTP 200. Retention: 2 years from 1 September 2025; an older project's 5-year period becomes 2 years on a plan change. |
| 38 | Nouwens et al., "Dark Patterns after the GDPR", CHI 2020 | arxiv.org/abs/2001.02479 | 2026-09-21 | VERIFIED, HTTP 200 |
| 39 | Octometer design document, decisions D12, D15, D18, D20, D24, D25, and sections 2.3, 4.1, 4.2, 8 | `docs/superpowers/specs/2026-09-21-octometer-design.md` | 2026-09-21 | VERIFIED |
| 40 | Octometer event log contract v1, rules C1 to C10 | `contract/README.md` | 2026-09-21 | VERIFIED |
| 41 | Article 29 Working Party WP248 rev.01, DPIA criteria | ec.europa.eu/newsroom/article29/redirection/document/47711 | 2026-09-21 | VERIFIED, HTTP 200 |
| 42 | EDPB Opinion 5/2019, ePrivacy/GDPR interplay | edpb.europa.eu/sites/default/files/files/file1/201905_edpb_opinion_eprivacydir_gdpr_interplay_en_0.pdf | 2026-09-21 | VERIFIED, HTTP 200 |

## Appendix A: Ukraine and the USA in detail

### A.1 Ukraine

Law No. 2297-VI, Article 11, gives **six** grounds for processing, not five: the common summary
omits ground 5, an obligation of the controller that the law provides (added by Law No. 383-VII
of 2013). Ground 6, legitimate interest, is narrower than usually stated: it covers the
controller "or a third party to whom the data are transferred", and it yields to the data
subject's **fundamental** rights. [VERIFIED, read 2026-09-21] Consent stays the safer ground,
for the same profiling reason as section 2.2.

**The transfer question has an answer today.** Article 29(3) treats a transfer as safe with no
extra tool to an EEA state, a Council of Europe Convention 108 state, and, since Law No.
3585-IX of 22 February 2024, a state whose capital-markets regulator signed the IOSCO
Multilateral MoU — the US SEC and CFTC both did, in 2002. [VERIFIED, iosco.org signatories
list, read 2026-09-21] **The USA is adequate under Ukrainian law on this route, so a transfer
to MongoDB Atlas, Fly.io, or Cloudflare needs no separate Ukrainian tool.** [INFERENCE] Cabinet
Resolution No. 910 (16 August 2022) lists countries only for the state web portal, "exclusively
for the purposes of that resolution", not a general Article 29(3) list. [VERIFIED, read
2026-09-21]

**Draft law No. 8153 has not passed.** It cleared the first reading on 20 November 2024
(Resolution No. 4065-IX). Resolution No. 4729-IX of 17 December 2025 sent it to committee for
the second reading; its bill card reads "being prepared for the second reading". [VERIFIED,
itd.rada.gov.ua/billInfo/Bills/Card/40707, read 2026-09-21]

**The complaint right is Article 8(2)(8):** a data subject may complain to the Ombudsman or to
the courts. [VERIFIED, Law No. 2297-VI Art. 8(2)(8), second regulator review, 2026-09-21]
Article 22 names the two control bodies (the Ombudsman and the courts); it is not itself the
complaint right. Article 12(2) needs a notice within 30 working days when data comes from a
source other than the data subject. Article 21(1) needs a notice within 10 working days of a
transfer to a third party, when consent requires it. [VERIFIED, read 2026-09-21]

**Article 9(1) needs an Ombudsman notice within thirty working days after a special-risk
processing starts, not before.** [VERIFIED, Law No. 2297-VI Art. 9(1), second regulator review,
2026-09-21] Ombudsman Order No. 1/02-14, clause 1.2, lists twelve data categories that trigger
this notice: racial origin, political and religious belief, party or union membership, health,
sex life, biometric and genetic data, criminal or administrative liability, pre-trial and
operational-search measures, violence, and location or routes of movement. [VERIFIED, Ombudsman
Order No. 1/02-14 clause 1.2, second regulator review, 2026-09-21] Click analytics falls
outside this list, unless an app records location, so the Article 9 notice does not apply.
[INFERENCE]

**One point favours the design already built.** Article 2, within the field of e-commerce,
allows an electronic tick at registration as consent, if "the system creates no possibility of
processing personal data before the tick". [VERIFIED, read 2026-09-21] The `start()` gate (D24)
already follows the same rule. [INFERENCE] Whether this e-commerce rule extends to a
non-commerce app is not confirmed. [NOT VERIFIED — ask a lawyer] Ombudsman Order No. 1/02-14
clause 2.8 requires the owner to keep the consent evidence for the whole processing period
(section 6). [VERIFIED, second regulator review, 2026-09-21]

### A.2 The USA

**CCPA/CPRA** (Cal. Civ. Code 1798.140(d)(1)) applies to a business that meets any one test:
gross revenue above USD 25,000,000, as adjusted for inflation — the CPPA's adjusted 2025 figure
is USD 26,625,000, next adjusted 1 January 2027. [VERIFIED, leginfo.legislature.ca.gov, read
2026-09-21; the dollar figure: VERIFIED, cppa.ca.gov, read 2026-09-21] It also applies to a
business that "alone or in combination, annually buys, sells, or shares the personal
information of 100,000 or more consumers or households" — collection alone never meets this
test — or gets 50 percent or more of its revenue from selling or sharing personal information.
One developer with a few small apps meets none of these tests today. [INFERENCE]

**Other state laws.** As of 8 September 2026, the IAPP's own map shades 23 US states as having
signed a comprehensive privacy law; its text elsewhere states a total of 19 states "to date", an
older count on the same page. [VERIFIED, IAPP tracker, read 2026-09-21] Texas (541.002(a)(3))
exempts a business under the federal Small Business Administration size standard; Nebraska
(87-1103(1)(c)) exempts a business under the federal Small Business Act definition. One
developer with a few small apps meets a small-business test under both statutes, so neither law
reaches this processing today. [INFERENCE] No state test turns on revenue alone, so USA
exposure does not end with California. Connecticut's 2026 amendment adds duties for a minor's
data and AI training data. [PARTIALLY VERIFIED, Connecticut's exact scope] Ask a lawyer before
a US consumer launch.

**COPPA** applies on two alternative triggers: a service "directed to children" under 13, or an
operator with "actual knowledge" of such a user — not actual knowledge alone. [VERIFIED, 16 CFR
312.2-312.3, ecfr.gov, read 2026-09-21] No app targets children today. The 2025 FTC amendments,
effective 23 June 2025, add a written retention policy (312.10) and a written information
security program (312.8(b)), and widen "personal information" to include a biometric
identifier. Most amended provisions carry a compliance date of 22 April 2026; three provisions
of 312.11 carry a different date. [VERIFIED, ftc.gov, federalregister.gov document 2025-05904,
read 2026-09-21]
