# Virtis ADK Java fork maintainer inventory

The exact upstream ADK Java 1.12.0 baseline is tag `v1.12.0`, commit
`4f2178cce329aa2ce7ffb94e2be399f5dfd370c2`. Its direct Virtis descendant,
the consumed fork commit, is `a671d98eb7731d6e73accc9ddcaf47f1cedad399`.
The former `923d602e...` baseline attribution was incorrect. This inventory
covers every fork change, including production, tests and documentation,
and then distinguishes the frontend-closure packet additions introduced in
Revision 8. Those additions, including the later optional recovery observer,
were checkpointed at `04abfdba8e2fa4a2e7e9f0faf2dc886a839f1a89`.
`git diff --stat v1.12.0..a671d98eb7731d6e73accc9ddcaf47f1cedad399`
reports exactly 19 files, 618 insertions and 11 deletions. Inspection of that
complete diff found no other pre-Revision-8 non-Live production delta.

The consumed fork's five behavior groups are:

- explicit queued-content `turnComplete`, including `false` for incomplete
  hidden instructions;
- `RunConfig.sessionResumption` propagation into `LiveConnectConfig`;
- provider resumption updates carried through `LlmResponse`, `Event`, builders,
  copies, and contentless event conversion;
- Gemini 3.1 Live realtime text/audio/video mapping while Gemini 2.5 retains
  its media behavior and incomplete system instructions remain client content;
- bounded Live diagnostics that contain only safe message-type presence, never
  provider bodies or opaque resumption handles.

GenerateContent and context-cache behavior comes from the upstream baseline,
not a Virtis patch. In particular, context caching is not Live session
resumption, and the Live transport never invokes GenerateContent cache
management. The existing public `GeminiLiveTransport` seam and SDK protocol,
provider authentication and model binding remain authoritative.

## Frontend closure packet additions

`RunConfig.Builder.contextWindowCompression(ContextWindowCompressionConfig)` is typed
and survives builder copies and `Basic` propagation into the actual
`LiveConnectConfig`. An empty `SlidingWindow` enables provider defaults when
the application selects it; ADK does not force 100,000/80,000 token thresholds.
Tests drive the real SDK setup serializer with only the socket effect mocked,
covering both empty provider defaults and explicitly authored numeric values.

Provider `GoAway` is carried as typed `liveGoAway` through `LlmResponse`,
`Event`, copies, JSON and contentless event conversion. The diagnostic reports
only the `go_away` kind. Live-flow cancellation disposes the existing sender
and closes the connection, including when its response stream has not ended.

Automatic provider `GoAway` rotation is enabled only for an explicitly
transparent session-resumption config. `Gemini` composes one private
`ResumableLiveTransport` beneath the unchanged ADK response stream and existing
serialized sender. Nontransparent or absent resumption uses the original
transport directly. `LiveRequestQueue` remains the input queue; there is no
new business queue, durable replay ledger or resume authority.
The pinned SDK admits `transparent` only in Vertex mode and rejects it in
Gemini Developer API mode; ordinary nontransparent handle setup remains
supported there. This packet's automatic rotation uses the selected Vertex
binding, not a transparent-resumption claim for both provider modes.

The helper records exact typed client-content, realtime text/audio/video/media
and tool-response parameters before SDK send. Setup is excluded; indices start
at 1 and reset after each resumed connection. A checkpoint is indivisible:
`resumable=true`, a nonempty handle and a present, nonnegative, monotonic
consumed index no greater than the current connection's highest sent index.
Only a valid checkpoint releases acknowledged payload references. An invalid
update invalidates the current checkpoint; its fields are never merged with
an older update. The existing typed update still reaches Gemini's fenced
durable handle owner.

The first GoAway installs a handoff barrier. The old sender stops, any
already-invoked send settles, old close is initiated and its public future
settles, and exactly one replacement opens with the latest checkpoint handle.
Only the unacknowledged suffix is replayed, in order, with indices starting
again at 1. New sends wait behind that handoff. A send failure with a trusted
checkpoint holds the original send future through recovery instead of ending
the flow prematurely. A failed replacement/replay is terminal; there is no
recursive reconnect loop. Duplicate old notifications cannot open another
sender. Closing/finalizing/cancelling the flow prevents another open and closes
a connection that completes late; stale old transport callbacks are ignored.

Positive `RunConfig.liveResumptionReplayMaxMessages` (`int`) and
`liveResumptionReplayMaxBytes` (`long`) default to 4,096 and 8,388,608.
`Basic` copies them into ADK-local `@JsonIgnore` fields on `LlmRequest`; they
never enter provider JSON. Bytes are the UTF-8 length of the exact typed
parameter's GenAI `JsonSerializable` representation, with checked addition
before retention. This includes JSON/base64 expansion, not only raw media
bytes. No ADK environment-variable reader or implicit product tuning exists.
Gemini owns central setting overrides and existing PII/retention/fencing.

Failures are `IllegalStateException` with a fixed message/code and no provider
cause: `LIVE_RECOVERY_REPLAY_WINDOW_EXHAUSTED` for capacity,
`LIVE_RECOVERY_CHECKPOINT_INVALID` for unavailable/inconsistent checkpoints,
and `LIVE_RECOVERY_DELIVERY_UNKNOWN` for ambiguous delivery without a trusted
index or failed recovery. These propagate through the existing Live error
stream. There is no blind replay, fresh-session fallback or blanket lossless
claim. Compression may discard old active-context turns; independently retained
scoring evidence and authored interview duration remain product policy.

The pinned GenAI Java SDK 1.77.0 registers a message consumer but exposes no
public post-connect socket-close/failure callback. Its close future queues a
close; it does not prove the socket's `onClosed` event. The guaranteed periodic
trigger is GoAway. Additional recovery applies only to a failure/end actually
surfaced by the public transport seam. Tests do not claim detection of an
SDK-hidden abrupt close. There is no reflection, provider-protocol replacement
or custom-HTTP-client hook; the SDK clones such clients and owns its listener.
No handle, media, transcript or provider body belongs in diagnostic output.

### Optional internal recovery observer

`RunConfig.Builder.liveRecoveryObserver(Consumer<RunConfig.LiveRecoveryEvent>)`
sets an optional internal observer; the default is null. The getter and the
corresponding `LlmRequest` field are `@JsonIgnore`. Builders/copies and `Basic`
carry the same observer to `Gemini` and the existing private transport helper;
it never enters provider setup or serialized requests. Ordinary callers add no
observer and retain their existing behavior. ADK adds no environment setting,
browser protocol, provider dependency or telemetry delivery authority.

`RunConfig.LiveRecoveryEvent` contains only the closed nested `Phase` enum
(`GO_AWAY_RECEIVED`, `ROTATION_COMPLETED`) and four `long` accessors:
`upstreamConnectionGeneration`, `acceptedCheckpointCount`,
`replayedMessageCount` and `replayedByteCount`. There are no handles, content,
payload-derived strings, credentials, user identifiers or provider bodies.
These safe counters are cumulative for one logical transport lifetime:

- Connection generation begins at 1 after an upstream transport actually
  opens successfully and increments for each successful replacement open.
- Each newly accepted valid handle/index checkpoint increments the checkpoint
  count. Exact duplicates and rejected updates do not; restoring the same
  checkpoint after an invalid update does not count it again.
- GoAway reports the old generation and the prior completed replay totals,
  after installing the sender barrier.
- Completion reports the new generation only after setup and the entire
  ordered replay suffix succeed, immediately before barrier release. Its
  cumulative replay totals include that suffix's exact typed UTF-8 byte count;
  empty replay adds zero. Queued new input is excluded from replay totals.

Each phase/generation pair is reported at most once. A failed or cancelled
replacement/replay emits no completion. Observer failures, including thrown
errors, are caught and ignored without logging their content; observation is
never a condition for recovery, replay, fencing or session continuity. Gemini
alone owns its guarded test-only installation and browser frame mapping.

`ROTATION_COMPLETED` proves the transport setup and local ordered replay
finished, not that the provider restored earlier conversation context or
consumed the replay. The SDK setup-complete type has no explicit resumed-state
acknowledgement, and this adapter does not compare provider session IDs.
Behavioral context continuity therefore needs separate provider proof. The
observer has no timestamps or per-stage durations: elapsed time until the next
persisted transcription cannot distinguish handoff/connect/replay latency from
provider response, speech/VAD, caller input timing or persistence latency.
There is no deliberate sleep/backoff or wait for `GoAway.timeLeft`; the helper
immediately begins its bounded single handoff and waits the existing futures.

## Verification

Focused and complete local gates are:

```sh
./mvnw -pl core -Dtest=RunConfigTest,BasicTest,GeminiLlmConnectionTest,RunnerTest,LiveRequestQueueTest,LlmRequestTest,LlmResponseTest,EventTest,ResumableLiveTransportTest test
env -u OPENAI_API_KEY -u ANTHROPIC_API_KEY -u GOOGLE_API_KEY -u GEMINI_API_KEY ./mvnw test
```

Revision 8 deterministic checks cover every outbound kind, exact SDK setup
serialization for Gemini API and Vertex with synthetic socket/credentials,
count/UTF-8-byte bounds, checkpoint validation/pruning, index reset, ordered
replay, queued new input, duplicate GoAway, public failure recovery, ambiguity,
late completion/cancellation and maximum-window stack safety. `RunnerTest`
proves contentless GoAway and cancellation through the real flow. The full
provider-disabled gate retains upstream context-cache and all reactor tests.
Observer tests also prove end-to-end configuration propagation, no
nontransparent notifications, checkpoint deduplication, cumulative counters
over multiple generations, exact replay bytes for every operation kind,
phase ordering, failure/cancellation suppression and sink-failure isolation.
The initial-resumption test also asserts the synthetic handle and transparent
flag in Vertex's actual serialized SDK setup, Developer API rejection of
transparent mode, and ordinary Developer API handle serialization. The
replacement test asserts that the newest accepted checkpoint supplies the
replacement config.
On 2026-10-21 the original expanded focused gate, including the recovery observer,
passed 254 primary tests with zero failures/errors/skips in 15.534 seconds.
The external-provider-disabled full gate passed all 19 reactor modules in
1:41; core's primary suite ran 2,394 tests with zero failures/errors and 24
skips. Configured additional
Surefire executions also passed. No pre-existing test failure was encountered.
The 2026-10-23 acceptance refresh, including the serialized resumption-handle
assertions, again passed 254 primary focused tests with no failures/errors/skips
in 17.200 seconds and all 19 provider-disabled reactor modules in 1:44; core
again ran 2,394 primary tests with no failures/errors and 24 skips. Configured
additional Surefire executions passed. Production source bytes were unchanged,
so this refresh supplements rather than invalidates the original full gate.
The execution handoff records the exact packaged artifact/source hashes.
Actual provider-issued GoAway, remembered-nonce continuity and observed
compression firing require separate authorized live proof; local socket
injection and accepted setup do not prove those observations.

Production publication/consumption requires a separately authorized reviewed
commit and immutable JitPack coordinates
`com.github.DecilAI2025.adk-java:google-adk:<full-commit-sha>`. The publication
handoff records that commit's full SHA and resolution proof after Git creates
the commit object. A branch name, moving reference, tag, or local Maven
artifact is not an acceptable production dependency or rollback/forward
mechanism. A checksum-addressed local candidate is development evidence only.
The separately authorized fork checkpoint does not publish an artifact or
change the production dependency pin.

## Patch inventory versus exact upstream base

| Patch | Production boundary | Regression coverage |
| --- | --- | --- |
| Explicit queued-content turn completion | `agents/LiveRequest.java`, `agents/LiveRequestQueue.java`, `models/BaseLlmConnection.java`, `flows/llmflows/BaseLlmFlow.java`, `models/GeminiLlmConnection.java` | `LiveRequestQueueTest`; `RunnerTest.runLive_contentWithTurnCompleteFalsePropagatesToConnection`; `GeminiLlmConnectionTest` |
| Live session-resumption configuration | `agents/RunConfig.java`, `flows/llmflows/Basic.java` | `RunConfigTest`; `BasicTest` |
| Provider resumption updates | `models/GeminiLlmConnection.java`, `models/LlmResponse.java`, `events/Event.java`, `flows/llmflows/BaseLlmFlow.java` | `GeminiLlmConnectionTest`; `LlmResponseTest`; `EventTest`; `RunnerTest.runLive_contentlessSessionResumptionUpdatePropagatesToEvent` |
| Gemini 3.1 Live input shapes | `models/Gemini.java`, `models/GeminiLlmConnection.java` | `GeminiLlmConnectionTest` model-specific text, audio, video, Gemini 2.5 media, and incomplete-system-content cases |
| Safe Live diagnostics | `models/GeminiLlmConnection.java`, `models/Gemini.java`, `flows/llmflows/BaseLlmFlow.java` | `GeminiLlmConnectionTest.diagnosticMessageTypes_reportsPresenceWithoutPayloadOrHandle`; absence of setup/event serialization in the complete fork diff |

## Upstream comparison and rebase decisions

On 2026-10-21, fetched and inspected exact newer upstream main
[`5f49fea45ed5f2d1b50b18165ea5370e9a55735a`](https://github.com/google/adk-java/tree/5f49fea45ed5f2d1b50b18165ea5370e9a55735a),
not the stale local `upstream/main`. This is a source comparison; fork tests
were not transplanted/run on that head, and no rebase or merge occurred.

| Delta / Virtis reason | Newer-upstream equivalent status at checked SHA | Keep/adapt/drop on rebase |
| --- | --- | --- |
| Explicit incomplete queued content prevents hidden instructions from completing a turn. | Upstream now has `LiveRequest.partial`, `isPartial`, and `sendContent(content, partial)` mapping to SDK `turnComplete(!partial)`. Behavior is available, but the same boolean overload has the **opposite polarity** from the consumed Virtis `turnComplete` API; no compatible end-to-end equivalence proof. | Adapt explicitly: invert the flag at a retained compatibility boundary or migrate every Virtis caller/test together. Never resolve this conflict by copying signatures. Drop redundant internal code only after false/true/system/tool cases pass. |
| Typed initial resumption config carries Gemini's persisted handle into setup. | `RunConfig.sessionResumption` and `Basic` setup propagation absent. | Keep; remove only after actual setup/copy tests prove an upstream replacement. |
| Provider handle updates reach Gemini's durable/fenced owner even without content. | No `liveSessionResumptionUpdate` fields/conversion in checked connection/response/event/flow. | Keep builders/copies/JSON/equality/hash and the contentless-event guard as one boundary. |
| Model-specific 3.1 text/audio/video shapes preserve selected-model behavior while 2.5 remains compatible. | Checked upstream connection still sends all realtime blobs through `media` and ordinary content through client content; it does not receive the resolved model name. | Keep/adapt the full model-name-to-SDK mapping and incomplete-system exception; do not infer equivalence from a newer SDK alone. |
| Bounded diagnostics prevent sensitive setup, provider messages, event bodies and handles entering logs. | Checked upstream still serializes received/unknown messages, setup config and model event/function-call data in diagnostics. | Keep all three production logging boundaries; retain safe presence-only regression. |
| Typed compression makes the selected setup enable provider-default sliding-window compression. | No RunConfig/Basic compression propagation in checked upstream. | Keep; actual outgoing setup/default/explicit-override tests decide removal. |
| Typed GoAway informs the product without treating a contentless lifecycle message as unknown. | No GoAway conversion/event field in checked upstream. | Keep response/event/copy/JSON/contentless flow pieces together. |
| Transparent replay rotates provider connection while retaining browser/run identity and queued input. | No bounded replay helper, matching handle/index checkpoint or handoff barrier in checked upstream. | Keep/adapt to future SDK lifecycle callbacks only when publicly supported; retain every failure/cancellation/index test and default bounds. |
| Cancellation/late-open cleanup prevents orphaned sender/provider work. | Checked upstream still cancels the derived connect future and has no Live flow `doOnCancel` cleanup. | Keep; prove late connection close and sender disposal before removal. |
| Optional safe recovery observation correlates the authorized long browser proof with the transport authority without exposing provider state. | No matching RunConfig/LlmRequest observer, closed scalar event or transport counters at the checked upstream SHA. | Keep optional/null by default, preserve JSON exclusion, cumulative counter semantics and failure isolation; adapt/drop only after equivalent provenance and ordering tests pass. |

The inherited GenerateContent/context-cache, finite scripted transport seam,
general event/model APIs and SDK versions are upstream code, not additional
Virtis changes. This is established by the complete baseline diff, rather than
by limiting the review to Live filenames. There are no pre-Revision-8 build,
dependency, migration, service or non-Live production patches to carry.

### Behavioral notes

`LiveRequestQueue.content(content, false)` retains the explicit incomplete-turn
flag through `BaseLlmFlow` and into `sendContent(content, false)`. The existing
one-argument API remains turn-complete by default.

`RunConfig.sessionResumption(...)` carries a persisted provider handle through
builder copies and `Basic` into `LiveConnectConfig`. Incoming provider updates
become `LlmResponse.liveSessionResumptionUpdate` and then
`Event.liveSessionResumptionUpdate`, including updates with no content. The
opaque update remains distinct from ordinary conversation content.

Resolved Gemini 3.1 Live models send ordinary text through the realtime text
field and map audio to realtime audio and image/video to realtime video.
Gemini 2.5 continues to use the existing media field. System-role content stays
on client-content transport so `turnComplete=false` remains expressible.

Live diagnostics derive only a fixed list of present message kinds. They never
serialize or interpolate a `LiveServerMessage`, provider body, media bytes, or
resumption handle.

## Upgrade and removal rule

For a later upstream base, compare every inventoried end-to-end boundary and remove a
patch only when upstream provides equivalent behavior and the corresponding
regression passes without the fork change. A similarly named continuation,
pause/resume, or context-cache API is not equivalent proof of provider Live
session resumption.

## Exhaustive changed-file appendix

Paths below are relative to this repository. `B` means changed between exact
upstream `v1.12.0` and consumed fork `a671d98...`; `R8` means additionally
changed/added by this packet. This union covers every fork production/test/doc
delta, not only new files. `A` marks a new file in that respective comparison.

| Path | B | R8 | Change / proof responsibility |
| --- | --- | --- | --- |
| `VIRTIS_LIVE_PATCH.md` | A | M | Complete maintainer inventory, provenance, verification, limitations and rebase decisions. |
| `core/src/main/java/com/google/adk/agents/LiveRequest.java` | M | — | Optional explicit turnComplete plus JSON/builder. |
| `core/src/main/java/com/google/adk/agents/LiveRequestQueue.java` | M | — | Explicit content completion overload; one input queue. |
| `core/src/main/java/com/google/adk/agents/RunConfig.java` | M | M | Typed resumption, compression, replay defaults/positive validation/copies; optional internal observer and closed scalar event record. |
| `core/src/main/java/com/google/adk/events/Event.java` | M | M | Typed resumption/GoAway, JSON, copies, equality and hash. |
| `core/src/main/java/com/google/adk/flows/llmflows/BaseLlmFlow.java` | M | M | Completion flag, contentless lifecycle conversion, safe logging and cancellation. |
| `core/src/main/java/com/google/adk/flows/llmflows/Basic.java` | M | M | Actual resumption/compression setup and local replay-bound/observer propagation. |
| `core/src/main/java/com/google/adk/models/BaseLlmConnection.java` | M | — | Backward-compatible completion overload with legacy implementation fallback. |
| `core/src/main/java/com/google/adk/models/Gemini.java` | M | M | Resolved model handed to connection, safe setup logging, transparent-only replay/optional observer composition. |
| `core/src/main/java/com/google/adk/models/GeminiLlmConnection.java` | M | M | Model-specific media/text, completion, lifecycle conversion, safe diagnostics and late-open cleanup. |
| `core/src/main/java/com/google/adk/models/LlmRequest.java` | — | M | Positive, nonserialized replay bounds, optional nonserialized observer, defaults/copy support. |
| `core/src/main/java/com/google/adk/models/LlmResponse.java` | M | M | Typed resumption/GoAway response fields/builders. |
| `core/src/main/java/com/google/adk/models/ResumableLiveTransport.java` | — | A | Single private checkpoint/window/handoff authority; safe failures and optional best-effort cumulative scalar observation. |
| `core/src/test/java/com/google/adk/agents/LiveRequestQueueTest.java` | A | — | Turn-complete defaults/false/queue JSON compatibility. |
| `core/src/test/java/com/google/adk/agents/RunConfigTest.java` | M | M | Typed setup/observer copies/defaults and replay bound rejection. |
| `core/src/test/java/com/google/adk/events/EventTest.java` | M | M | Lifecycle JSON/copies/equality. |
| `core/src/test/java/com/google/adk/flows/llmflows/BasicTest.java` | M | M | Setup/local observer propagation and actual SDK serializer for both provider modes. |
| `core/src/test/java/com/google/adk/models/GeminiLlmConnectionTest.java` | M | M | Retained media/completion/resumption/safe diagnostics; GoAway and late close. |
| `core/src/test/java/com/google/adk/models/LlmRequestTest.java` | — | M | Local bounds/observer defaults/copies/validation/JSON exclusion. |
| `core/src/test/java/com/google/adk/models/LlmResponseTest.java` | M | M | Lifecycle serialization and copies. |
| `core/src/test/java/com/google/adk/models/ResumableLiveTransportTest.java` | — | A | Transparent-only selection, exact replay, bounds, indices, ambiguity, cancellation, one sender, cumulative scalar observer semantics and sink-failure isolation. |
| `core/src/test/java/com/google/adk/runner/RunnerTest.java` | M | M | Completion flag and contentless lifecycle through real flow; cancellation stops sender. |
| `core/src/test/java/com/google/adk/testing/TestLlm.java` | M | — | Test connection records explicit completion flag, allowing the real-flow regression. |

Reproduce the baseline list with
`git diff --name-status v1.12.0..a671d98eb7731d6e73accc9ddcaf47f1cedad399`;
compare `git diff --name-status a671d98eb7731d6e73accc9ddcaf47f1cedad399..HEAD`
for the committed packet list, adding any explicitly identified working-tree
changes during implementation. Generated `target/`
artifacts and packet execution notes are not fork source deltas.
