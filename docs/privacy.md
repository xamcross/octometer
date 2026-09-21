# Octometer privacy proposal: legal basis and consent for click tracking

Status: PROPOSAL of 2026-09-21, revised after two reviews. The owner has not decided yet.

This document is not legal advice. It is a technical proposal for the owner to approve or
reject. Get a lawyer's check for each point in section 10, "When to ask a lawyer".

## 1. The decision in one page

**Recommendation: Mode A. Ask for consent before the tracker starts. Record nothing for a
user who refuses.** This is the only mode that needs no design change, matches the tool that
the industry ships for a persistent per-user id (PostHog `on_reject`, Matomo
`requireConsent()`), and removes the ePrivacy Article 5(3) question at once: no consent, no
`sessionStorage` write, no send. [VERIFIED, PostHog docs and Matomo developer guide, read
2026-09-21]

**Cost to the owner.** One consent panel, one settings toggle, and one erasure call, built
once in the kit and wired into each app (section 9). No contract change.

**What the owner does next.** Approve Mode A below, approve the app table, and start the
`investguideua` pilot work of section 9.

**The expected rate.** Plausible's own benchmark page states an EU/EEA acceptance rate of 40
to 50 percent for an equal-choice panel, and a mixed global audience at 50 to 60 percent.
[VERIFIED, plausible.io/cookie-banner-traffic-loss-calculator, read 2026-09-21; a vendor
source, since Plausible sells a cookieless product] An academic study of 680 UK sites found
that removing an equal "reject" button raises the accept rate by 22 to 23 percentage points.
[VERIFIED, Nouwens et al., CHI 2020, arxiv.org/abs/2001.02479, read 2026-09-21] **Effect on the
monitor: expect the level 1 totals to show under half of real clicks once consent is equal and
honest. A count from one app is not comparable with a count from another app at a different
rate, and the two totals never equal the app's real traffic.**

### The apps

| App | Kind | Stack | Rollout order |
|---|---|---|---|
| `investguideua` | Consumer, signed-in users | Spring Boot, Angular | 1, the pilot |
| `traficio` | Consumer, signed-in users | Ktor, Angular | 2 |
| `tuliplot` | Consumer, signed-in users | Spring Boot, Angular | 3 |
| `cadence` | B2B, employees of business customers | Spring Boot, Angular, Cloudflare Pages | 4, last |

The design document of 2026-09-21 proposed `traficio` as the pilot (design section 8). The
owner has since chosen `investguideua`. Users of all four apps are in Ukraine, in the EU, and
in the USA (owner statement, 2026-09-21).

### The four modes, judged honestly

| Mode | What it does | Needs a legal check |
|---|---|---|
| A. Full consent (recommended) | Consent gate before `start()`. Nothing recorded on refusal. | Yes — Article 27 still applies once an EU user is in scope (section 2). |
| B. The middle way | Consent for the user level. A refusal still writes the event with `userId = null`. | Yes — the `sessionStorage` write and the send of the click still trigger ePrivacy Article 5(3), even with no `userId` (section 2.1). Also needs a contract change: rule C6 already gives `null` the meaning "not signed in" (contract C6); a refusing signed-in user needs a new value, or the anonymous bucket mixes two different populations. |
| C. Server-derived session id | The app derives `sessionId` server-side from its own login-session cookie. The tracker writes nothing to `sessionStorage`. | Yes — nothing is stored or read by the tracker itself, so Article 5(3) likely does not apply. But reusing a login cookie for analytics is a new purpose under Article 5(1)(b), and the legal basis then moves to Article 6(1)(f), which needs a balancing test, a transparent notice, and an Article 21(1)(f) objection path. **Needs a design change: a new event-log contract version, because the tracker stops generating and storing its own `sessionId` (rule C5), and the server assigns it instead (rule C10, a major contract change).** |
| D. No user id at all | The event drops `userId`. Only app-level and element-level totals remain. | Yes. The first version of this document called Mode D free of consent. **That is false.** No EU-wide statistics exemption exists: CNIL's rests on Article 82 of the French Data Protection Act, and Germany's TDDDG section 25 has only two narrow exceptions, neither for statistics. [VERIFIED, gesetze-im-internet.de/ttdsg/__25.html, read 2026-09-21] The `sessionStorage` write and the send of the click still happen, so Article 5(3) still applies in Germany and in any state with no exemption. |

None of the four modes is consent-free. Mode A is the only one that removes the question
instead of narrowing it.

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
`sessionStorage` write, and its send of the session id, both meet this test.

**The exemptions are narrower than the first draft said, and they are national, not EU-wide.**
CNIL's statistics exemption keeps a 13-month tracker lifetime, a 25-month cap on the collected
data, no cross-match with other processing, one publisher only, and it keeps the user's
objection path — it is an opt-out regime, not "no consent needed". [VERIFIED, CNIL Sheet 16 and
the CNIL page of 4 July 2025, read 2026-09-21] The ICO's exception is a purpose test — "the sole
purpose of the storage or access is to enable the person... to collect information for
statistical purposes about how the service is used... with a view to making improvements to
the service" — and the same page states that a visitor id connected to site activity still
needs consent. [VERIFIED, ico.org.uk, storage and access technologies, "what are the
exceptions", read 2026-09-21] Germany's TDDDG section 25 has no statistics exemption at all.
**Octometer keeps a user id at user level, so no version of the CNIL or ICO exemption applies,
and Germany has none to apply.**

**Article 5(3) is avoidable by design, not just by consent** (paragraph 56). If the server
derives `sessionId` from the app's own strictly-necessary login cookie, the tracker stores and
reads nothing on the device, and Article 5(3) does not apply. This is Mode C above. It needs a
contract change, so it is not the MVP recommendation.

Ukraine has no direct counterpart of the ePrivacy Directive. Its electronic-communications law
(No. 1089-IX, adopted 2020) follows the European Electronic Communications Code, not the
ePrivacy Directive. [PARTIALLY VERIFIED — the law's existence and number are confirmed; its
in-force date and its content on device storage are NOT VERIFIED from a source read this
session — ask a lawyer]

### 2.2 The legal basis (GDPR Article 6, Ukraine Article 11)

Consent stays the safer basis, because the exemptions of 2.1 exclude user-level data, and a
per-user click history up to a still-undecided retention (issue #60) is profiling under Article
21(1), which covers point (e) **or** (f) of Article 6(1), not (f) alone. [VERIFIED,
eur-lex.europa.eu, CELEX 32016R0679, read 2026-09-21]

### 2.3 Does the GDPR apply to this owner?

- **Article 3(2)(a)**, offering a service to EU users, needs a targeting test: a named Member
  State, a non-local language, currency, or top-level domain. [VERIFIED, EDPB 3/2018 pages
  17-18] Run this test for each app before the pilot.
- **Article 3(2)(b)**, monitoring behaviour, lists cookie and other tracking techniques as an
  indicator. [VERIFIED, EDPB 3/2018 page 20] **The GDPR applies to every EU user, on this route
  alone.**
- **Article 27(1)** needs a representative "designated in writing", "where Article 3(2)
  applies" — not once a risk appears. [VERIFIED, eur-lex.europa.eu] The 27(2)(a) exemption needs
  processing that is occasional, has no large-scale special-category data, and is "unlikely to
  result in **a** risk" — not "a high risk". [VERIFIED, eur-lex.europa.eu; EDPB 3/2018 page 26]
  "Occasional" means not regular and outside the regular course of business (EDPB 3/2018 page
  25). Continuous click tracking is neither. **The owner needs an EU representative from the
  first day an EU user's tracker runs.** This needs a lawyer's check.
- **Chapter V** does not apply to an EU user's own click reaching the owner's app directly.
  [VERIFIED, EDPB 05/2021] The reason is that the owner sends the data to no other controller
  or processor (paragraph 31), not "direct collection" alone (a cookie disclosure is a
  transmission by the website operator, not the data subject: footnote 15, page 9). The owner
  must still weigh the third country's legal framework and tell the user their data leaves the
  EU, even with no formal transfer (paragraphs 31-34).
- **Article 8** applies because the basis is consent: valid at 16, or lower if a Member State
  sets it, never below 13; below that, consent needs a parental-responsibility holder, with
  "reasonable efforts to verify" it. [VERIFIED, eur-lex.europa.eu, Art. 8(1)-(2)] No app asks
  for an age today. Add this to section 10.
- **The B2B app (`cadence`).** The owner decides the purpose, so he is an **independent
  controller**, not a processor (Article 28(10)). [VERIFIED] He needs the customer's permission
  in the contract before he may process for his own purpose at all (Article 28(3)(a)) — a
  precondition, not optional support text. Employee consent stays valid because the employer
  cannot read the result: the monitor binds to `127.0.0.1` with no login (D12), so no named
  employee's clicks reach the employer. Free consent still needs "no adverse consequences at
  all" (EDPB 05/2020 paragraph 22), against a known power imbalance (paragraph 24).

## 3. Ukraine

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
to MongoDB Atlas, Fly.io, or Cloudflare needs no separate Ukrainian tool.** Cabinet Resolution
No. 910 (16 August 2022) lists countries only for the state web portal, "exclusively for the
purposes of that resolution", not a general Article 29(3) list. [VERIFIED, read 2026-09-21]

**Draft law No. 8153 has not passed.** It cleared the first reading on 20 November 2024
(Resolution No. 4065-IX). Resolution No. 4729-IX of 17 December 2025 sent it to committee for
the second reading; its bill card reads "being prepared for the second reading". [VERIFIED,
itd.rada.gov.ua/billInfo/Bills/Card/40707, read 2026-09-21]

**Duties the first version missed:** Article 22 gives a complaint route to the Ombudsman **and**
to the courts. Article 12(2) needs a notice within 30 working days when data comes from a
source other than the data subject. Article 21(1) needs a notice within 10 working days of a
transfer to a third party, when consent requires it. [VERIFIED, read 2026-09-21] Article 9 needs
an Ombudsman notice before a special-risk processing starts; Order No. 1/02-14 sets the risk
list, but its content could not be read this session. [NOT VERIFIED whether click analytics is
on that list — ask a lawyer]

**One point favours the design already built.** Article 2 allows an electronic tick at
registration as consent, if "the system creates no possibility of processing personal data
before the tick" — the same rule the `start()` gate (D24) already follows. [VERIFIED, read
2026-09-21] Ukrainian law places no duty to record or prove consent; that rests on GDPR Article
7(1) alone.

## 4. The USA

**CCPA/CPRA** (Cal. Civ. Code 1798.140(d)(1)) applies to a business that meets any one test:
gross revenue above USD 26,625,000 (the CPI-adjusted 2025 figure, next adjusted 1 January
2027); or "alone or in combination, annually buys, sells, or shares the personal information of
100,000 or more consumers or households" — collection alone never meets this test; or 50
percent or more of revenue from selling or sharing personal information. [VERIFIED,
leginfo.legislature.ca.gov, read 2026-09-21] One developer with a few small apps meets none of
these tests today.

**Other state laws.** About nineteen other US states now have a comprehensive privacy law in
force. [VERIFIED, IAPP tracker, read 2026-09-21] Texas and Nebraska apply their laws to any
business that is not an SBA-defined small business, with no fixed dollar or record threshold.
Connecticut's 2026 amendment adds duties for a minor's data and AI training data. [VERIFIED,
general rule; PARTIALLY VERIFIED, Connecticut's exact scope] No test turns on revenue alone, so
USA exposure does not end with California. Ask a lawyer before a US consumer launch.

**COPPA** applies on **two alternative triggers**: a service "directed to children" under 13,
or an operator with "actual knowledge" of such a user — not actual knowledge alone. [VERIFIED,
16 CFR 312.2-312.3, ecfr.gov, read 2026-09-21] No app targets children. The 2025 FTC amendments
(effective 23 June 2025, full compliance 22 April 2026) add a written retention policy
(312.10), a written security programme (312.8), and widen "personal information" to a
biometric identifier. [VERIFIED, ftc.gov, federalregister.gov 2025-05904, read 2026-09-21]

## 5. The processors

| Processor | Its own stated transfer tool | Status |
|---|---|---|
| MongoDB Atlas | EU-US Data Privacy Framework, self-certified, **limited to a transfer from the EEA, the UK, or Switzerland** | VERIFIED, mongodb.com/legal/data-privacy-framework-statement, read 2026-09-21 — does not cover an owner established in Ukraine |
| Fly.io | EU-US Data Privacy Framework (plus the UK and Swiss extensions); an SCC clause covers only Fly.io's own vendors | VERIFIED, fly.io/legal/privacy-policy, read 2026-09-21. `fly.io/legal/dpa` still returns HTTP 404 |
| Cloudflare | Standard Contractual Clauses for a "Restricted Transfer"; the DPF certification takes a US transfer **outside** that regime | VERIFIED, DPA version 6.4, effective 3 April 2026, cloudflare.com/cloudflare-customer-dpa, read 2026-09-21 |

**None of the three vendor tools reaches an owner established in Ukraine as the exporter.**
MongoDB's DPF statement limits it to the EEA, the UK, or Switzerland. Fly.io's SCC clause
covers its vendors, not its own customers. Cloudflare's SCCs need a "Restricted Transfer" under
EU law, which an export from Ukraine is not. **Under Ukrainian law the USA transfer is already
safe (section 3, the IOSCO route). Under GDPR, once an EU user's data reaches these US
processors, the safest documented step is the owner's own Article 46 SCC as exporter, on top of
each vendor's own tool** — ask a lawyer to confirm this.

**The Data Privacy Framework carries an open risk.** The Commission's adequacy decision of 10
July 2023 stands; the EU General Court dismissed an annulment action (Latombe, T-553/23) on 3
September 2025. [VERIFIED] A reported CJEU appeal could not be confirmed this session. [NOT
VERIFIED] The PCLOB shows one sitting member, consistent with reports of no quorum since early
2025, exact date unconfirmed. [PARTIALLY VERIFIED, pclob.gov/Board/Index] A claim of an EDPB
letter of 31 July 2026 on DPF validity, after Trump v. Slaughter (confirmed, 29 June 2026),
could not be found on the EDPB's own news page. [NOT VERIFIED — do not rely on this] **Treat
the DPF as active but under review, keep Cloudflare's SCC fallback as the safety net, and
re-check this section every six months.**

## 6. The consent method

- **Place.** A first-run panel in the app, shown once per user, apart from any other cookie
  banner.
- **Text.** Plain words. Name the controller ("we" = the owner, by name). State the right to
  withdraw at any time, before the choice, as Article 7(3) needs. [VERIFIED, eur-lex.europa.eu,
  read 2026-09-21] Link to the full notice of section 7. State the retention as a link to that
  notice, not as a number, because issue #60 has not set the number.
- **Buttons.** "Allow" and "Refuse", equal size, equal colour, equal weight — not just equal
  size, per EDPB 03/2022 on deceptive design patterns and the EDPB Cookie Banner Taskforce
  report of 18 January 2023. [VERIFIED, edpb.europa.eu, read 2026-09-21]
- **Default.** Off. No pre-ticked box. `start()` never runs before a choice (rule D24).
- **No answer yet.** A user who closes the panel without a choice stays in an explicit "unknown"
  state. The panel returns at the next sign-in, up to three times, then stops asking and stays
  off.
- **A text change.** A new notice version asks again. The old consent record stays, next to the
  new one (append-only, section 8).
- **Withdrawal.** A two-way toggle in Settings, showing the current state, as easy to reach as
  the first choice. [VERIFIED, Article 7(3), read 2026-09-21] A withdrawal calls `stop()` **and**
  `EventLogStore.deleteByUserId` (decision D18) for the app's own store. The monitor delete
  waits for **one poll cycle** after the app delete, then runs its own delete — the order of
  decision D15 is app, then one poll cycle, then monitor. A two-step order skips this wait, and
  a poll in flight can copy the deleted rows back.
- **The record.** A collection separate from the event log. One append-only document for each
  choice: `userId`, the time, the notice version, a copy of the shown text, and a state field
  with three values — `unknown`, `allowed`, `refused`. No IP address, no device fingerprint.
  [VERIFIED, EDPB 05/2020 paragraph 108, read 2026-09-21]

## 7. Draft privacy notice text (issue #42 approves it)

> **Click tracking in this app**
> The controller is `<owner name, contact address>`. [Add the EU representative once section
> 2.3 confirms the duty.]
> We record your clicks so we can improve this app. Each record holds the time, the name of
> the button or link, a random session id for your browser tab, and your account id. It never
> holds your name, your email address, or your IP address, except that a request with no
> signed-in user may use your IP address for one minute, in memory only, to limit abuse (rule
> D20).
> Legal basis: your consent (GDPR Article 6(1)(a); Ukraine Article 11(1)).
> Recording is voluntary. A refusal changes nothing else in the app.
> We keep a record in this app's database for a retention set in Settings and in `<link to the
> current retention decision>`, and for a separate period, also linked there, in the owner's
> local product monitor — a computer that the developer controls. Issue #60 has not fixed a
> final number.
> We use MongoDB Atlas to store the record, Fly.io to run this app, and Cloudflare to serve its
> pages, each outside the EU. We use the safeguards of section 5 of the full policy at
> `<link>`. We do not sell your data, and we do not give it to an advertiser.
> You may allow or refuse this recording at any time in Settings. You may ask us to show,
> correct, delete, or export your record, or to pause its use. You may complain to your
> national data protection authority. Contact: `<owner contact address>`.

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

**DPIA screening.** Consent makes the processing lawful; it does not lower the risk, and it is
not a criterion of Article 35. [VERIFIED, eur-lex.europa.eu, Art. 35(4)] A real screening
weighs the WP248 criteria against the design's own stated risks: systematic monitoring of a
signed-in user over a still-undecided retention, an access list open to the whole internet
with no audit, and a monitor with no login. **Judgement: a DPIA is not mandatory at MVP scale
— not special-category data, a small user count — but the owner re-screens before the second
app launches, and treats the open access list and the login-free monitor as accepted residual
risks (design section 8), not as reasons to skip Article 35(4).**

**Erasure path.** `EventLogStore.deleteByUserId` (D18) deletes the app's own events. The order
is: the app delete, then one full poll cycle, then the monitor's own delete (decision D15). A
withdrawal (section 6) and a data-subject erasure request both use this path.

## 9. The cost for an MVP

**Cannot wait, `investguideua` only:**
- Consent store and read/write API in the kit — Medium.
- Angular consent panel, with the text of section 6 — Medium.
- Settings toggle, a two-way state control — Small.
- `start()`/`stop()` wiring to the consent state — Small.
- Withdrawal path: `deleteByUserId` plus the D15 wait — Medium.
- Notice page, with a version id and a copy of the shown text — Small.

**Can wait until after the `investguideua` pilot:**
- A shared kit-level consent module for `traficio`, `tuliplot`, and `cadence` — Large.
- The monitor's own consent-rate view, per app — Medium.
- Rollout of each other app's consent panel — Medium, each.
- The DPIA re-screen of section 8 — Small.
- The B2B contract-terms check for `cadence` (section 2.3) — Small.
- The Article 27 representative appointment, once confirmed — Medium.

## 10. What the owner decides

- Use Mode A (full consent), not Mode B, C, or D, as the shipped method. **Recommend: yes.**
- Keep one global consent standard for every app and every user. **Recommend: yes.**
- Check `cadence`'s customer contracts for a product-analytics permission line, before the
  B2B rollout. **Recommend: yes.**
- Get a paid EU representative under Article 27, before the `investguideua` pilot reaches an
  EU user. **Recommend: yes, after the lawyer's check below.**
- Add an age question or an age gate to a consumer app, for Article 8. **Recommend: yes, before
  a consumer app is known to reach a user under 16.**
- Approve the draft notice text of section 7 for issue #42, once the retention placeholder is
  filled by issue #60. **Recommend: yes.**
- Keep Mode C (server-derived session id) as a later goal, not the MVP. **Recommend: yes.**

**When to ask a lawyer:**
- Is the processing "occasional" under Article 27(2) for this owner? (Section 2.3.)
- Does the owner need his own Article 46 SCC as exporter, on top of each processor's own tool?
  (Section 5.)
- Is `1089-IX` free of a device-storage consent rule, and does an Article 9 notice to the
  Ukrainian Ombudsman apply? (Sections 2.1 and 3.)
- Does Mode C's reuse of a login cookie for analytics survive an Article 6(1)(f) balancing
  test? (Section 1.)
- Does the B2B employer-employee relation create a joint-controller question for `cadence`?
  (Section 2.3.)

## 11. Sources

| # | Source | URL | Read | Status |
|---|---|---|---|---|
| 1 | ePrivacy Directive, consolidated Art. 5(3) | eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:02002L0058-20091219 | 2026-09-21 | VERIFIED |
| 2 | EDPB Guidelines 2/2023, technical scope Art. 5(3) | edpb.europa.eu/system/files/2024-10/edpb_guidelines_202302_technical_scope_art_53_eprivacydirective_v2_en_0.pdf | 2026-09-21 | VERIFIED |
| 3 | EDPB Guidelines 05/2020 on consent | edpb.europa.eu/system/files/documents/files/file1/edpb_guidelines_202005_consent_en.pdf | 2026-09-21 | VERIFIED |
| 4 | GDPR (OJ L 119, 4.5.2016) | eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679 | 2026-09-21 | VERIFIED |
| 5 | EDPB Guidelines 3/2018, territorial scope | edpb.europa.eu/system/files/documents/files/file1/edpb_guidelines_3_2018_territorial_scope_after_public_consultation_en_1.pdf | 2026-09-21 | VERIFIED |
| 6 | EDPB Guidelines 05/2021, Art. 3 / Chapter V | edpb.europa.eu/system/files/documents/2023-02/edpb_guidelines_05-2021_interplay_between_the_application_of_art3-chapter_v_of_the_gdpr_v2_en_0.pdf | 2026-09-21 | VERIFIED |
| 7 | EDPB Guidelines 03/2022, deceptive design patterns | edpb.europa.eu/system/files/2023-02/edpb_03-2022_guidelines_on_deceptive_design_patterns_in_social_media_platform_interfaces_v2_en_0.pdf | 2026-09-21 | VERIFIED |
| 8 | EDPB Cookie Banner Taskforce report, 18 Jan 2023 | edpb.europa.eu/system/files/2023-01/edpb_20230118_report_cookie_banner_taskforce_en.pdf | 2026-09-21 | VERIFIED |
| 9 | CNIL Sheet 16 and the CNIL page of 4 July 2025 | cnil.fr/en/sheet-ndeg16 ; cnil.fr/fr/cookies-solutions-pour-les-outils-de-mesure-daudience | 2026-09-21 | VERIFIED |
| 10 | ICO, storage and access technologies, exceptions | ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-exceptions/ | 2026-09-21 | VERIFIED |
| 11 | Germany TDDDG section 25 | gesetze-im-internet.de/ttdsg/__25.html | 2026-09-21 | VERIFIED |
| 12 | Law of Ukraine No. 2297-VI, Art. 2, 9, 11, 12, 21, 22, 29(3) | zakon.rada.gov.ua/laws/show/en/2297-17 ; protocol.ua mirror pages | 2026-09-21 | VERIFIED (Ukrainian text) |
| 13 | Law No. 383-VII (2013 amendment to Art. 11) | zakon.rada.gov.ua/laws/show/383-18 | 2026-09-21 | VERIFIED |
| 14 | Law No. 3585-IX (22 Feb 2024) and the IOSCO MMoU signatory list | iosco.org/about/?subsection=mmou&subsection1=signatories | 2026-09-21 | VERIFIED |
| 15 | Cabinet Resolution No. 910 (16 Aug 2022) | zakon.rada.gov.ua/laws/show/910-2022-п | 2026-09-21 | PARTIALLY VERIFIED (paraphrase, not raw text) |
| 16 | Draft law No. 8153, status, Resolutions 4065-IX and 4729-IX | itd.rada.gov.ua/billInfo/Bills/Card/40707 ; zakon.rada.gov.ua/laws/show/4065-20 ; /4729-20 | 2026-09-21 | VERIFIED |
| 17 | Ombudsman Order No. 1/02-14 | zakon.rada.gov.ua/rada/show/v1_02715-14 | 2026-09-21 | PARTIALLY VERIFIED (existence only) |
| 18 | Law No. 1089-IX, On Electronic Communications | zakon.rada.gov.ua/laws/show/1089-20 | 2026-09-21 | PARTIALLY VERIFIED (existence and number only) |
| 19 | Cal. Civ. Code 1798.140(d)(1), CCPA/CPRA threshold | leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=CIV&sectionNum=1798.140 | 2026-09-21 | VERIFIED |
| 20 | CPPA, CPI-adjusted threshold figure | cppa.ca.gov/regulations/cpi_adjustment.html | 2026-09-21 | VERIFIED |
| 21 | Texas TDPSA and Nebraska Data Privacy Act, applicability rule | tdpsa.org/section-541-107-requirements-for-small-businesses/ ; nebraskalegislature.gov/laws/display_html.php?begin_section=87-1101&end_section=87-1130 | 2026-09-21 | VERIFIED (substance) |
| 22 | Connecticut Public Act 25-113 (SB 1295) | wiley.law/alert-Major-Changes-to-Connecticut-Consumer-Privacy-Law-Will-Take-Effect-July-1-2026 | 2026-09-21 | PARTIALLY VERIFIED |
| 23 | IAPP US State Privacy Legislation Tracker | iapp.org/resources/article/us-state-privacy-legislation-tracker | 2026-09-21 | VERIFIED |
| 24 | COPPA, 16 CFR 312.2-312.3 | ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.3 | 2026-09-21 | VERIFIED |
| 25 | COPPA 2025 amendments | ftc.gov/news-events/news/press-releases/2025/01/ftc-finalizes-changes-childrens-privacy-rule ; federalregister.gov document 2025-05904 | 2026-09-21 | VERIFIED |
| 26 | MongoDB Data Privacy Framework statement | mongodb.com/legal/data-privacy-framework-statement | 2026-09-21 | VERIFIED |
| 27 | Fly.io privacy policy; `fly.io/legal/dpa` | fly.io/legal/privacy-policy ; fly.io/legal/dpa (404) | 2026-09-21 | VERIFIED |
| 28 | Cloudflare GDPR page and DPA v6.4 | cloudflare.com/trust-hub/gdpr/ ; cloudflare.com/cloudflare-customer-dpa/ | 2026-09-21 | VERIFIED |
| 29 | DPF adequacy decision and Latombe v Commission, T-553/23 | eucrim.eu/news/general-court-confirms-adequacy-of-us-data-protection/ | 2026-09-21 | VERIFIED |
| 30 | PCLOB board composition | pclob.gov/Board/Index | 2026-09-21 | PARTIALLY VERIFIED |
| 31 | A CJEU appeal of Latombe; an EC periodic DPF review; an EDPB letter of 31 July 2026 on DPF validity | — | 2026-09-21 | NOT VERIFIED, all three |
| 32 | PostHog docs, cookieless mode | posthog.com/docs/privacy/data-collection | 2026-09-21 | VERIFIED |
| 33 | Matomo tracking-consent guide and `config_id` FAQ | developer.matomo.org/guides/tracking-consent ; matomo.org/faq/general/how-is-the-visitor-config_id-processed/ | 2026-09-21 | VERIFIED |
| 34 | Matomo's own CNIL self-assessment FAQ | matomo.org/faq/how-to/how-do-i-configure-matomo-without-tracking-consent-for-french-visitors-cnil-exemption/ | 2026-09-21 | VERIFIED |
| 35 | Plausible data policy and traffic-loss calculator | plausible.io/data-policy ; plausible.io/cookie-banner-traffic-loss-calculator | 2026-09-21 | VERIFIED |
| 36 | Amplitude browser SDK docs, `setOptOut` | amplitude.com/docs/sdks/analytics/browser/browser-sdk-2 ; amplitude.com/docs/apis/analytics/user-privacy | 2026-09-21 | VERIFIED |
| 37 | Mixpanel JS SDK docs and GDPR page | docs.mixpanel.com/docs/tracking-methods/sdks/javascript ; mixpanel.com/legal/mixpanel-gdpr | 2026-09-21 | VERIFIED (opt-out control); NOT VERIFIED (a fixed 2-year/5-year retention figure) |
| 38 | Nouwens et al., "Dark Patterns after the GDPR", CHI 2020 | arxiv.org/abs/2001.02479 | 2026-09-21 | VERIFIED |
| 39 | Octometer design document, decisions O4, O5, D12, D15, D19-D24, sections 2.3, 4.1, 4.2, 8 | `docs/superpowers/specs/2026-09-21-octometer-design.md` | 2026-09-21 | VERIFIED |
| 40 | Octometer event log contract v1, rules C5, C6, C10 | `contract/README.md` | 2026-09-21 | VERIFIED |
