# Virtis ADK Java fork maintainer inventory

The exact upstream ADK Java baseline is tag `v1.14.0`, commit
`b45fae72c2b5a132070bdd2a8cdc5ad9ff783fda`, on branch `virtis_adk_1.14.0`.
It is a port of the accepted `v1.12.0`-based Virtis runtime commit
`baa5801ea110b54616977e5a24b4cb6f0761c5cf` (history:
`a671d98e`, `04abfdba`, `3d6e5adc`, `baa5801e`). The immutable consumer
coordinate for this line is
`com.github.DecilAI2025.adk-java:google-adk:<full-sha-of-branch-head>`; a
branch name or tag is never a production pin.

## Upstream turn-completion adaptation (Virtis `turnComplete` API removed)

The earlier fork added `LiveRequest.turnComplete`, `LiveRequestQueue.content(content,
turnComplete)` and `BaseLlmConnection.sendContent(content, turnComplete)`.
Upstream `v1.13+` provides the equivalent surface with opposite polarity, so
**the Virtis `turnComplete` surface is dropped and upstream is used as-is**:

- `LiveRequest.partial` / `isPartial()`;
- `LiveRequestQueue.content(Content)` is complete (`partial=false`);
- `LiveRequestQueue.content(Content, boolean partial)`;
- `BaseLlmConnection.sendContent(Content, boolean partial)` mapped by
  `GeminiLlmConnection` to SDK `turnComplete(!partial)`.

Migration for callers of the old fork API: `content(c, turnComplete=false)`
becomes `content(c, /* partial= */ true)`; `content(c, true)` becomes
`content(c, false)` (or the one-argument form); `sendContent(c, turnComplete)`
becomes `sendContent(c, !turnComplete)`. `LiveRequest.turnComplete()` and its
JSON property no longer exist; use `partial`. Fork files `LiveRequest.java`,
`LiveRequestQueue.java`, `BaseLlmConnection.java`, `LiveRequestQueueTest.java`
and `TestLlm.java` are identical to upstream `v1.14.0`.

## Retained Virtis behavior groups

- `RunConfig.sessionResumption` propagation into `LiveConnectConfig`;
- provider resumption updates carried through `LlmResponse`, `Event`, builders,
  copies, and contentless event conversion;
- Gemini 3.1 Live realtime-text mapping plus resolved Live Gemini 3.x
  split-media mapping (audio field for audio, video field for image/video); the
  resolved model name is passed into the connection. Gemini 3.8 text and Gemini
  2.5 text/media keep client-content/media paths. System-role content stays on
  client-content so incomplete (`partial=true`) turns remain expressible;
- bounded Live diagnostics that contain only safe message-type presence, never
  provider bodies or opaque resumption handles;
- typed context-window compression on `RunConfig`, propagated by `Basic`;
- typed `GoAway` on `LlmResponse`/`Event` with contentless flow conversion;
- `ResumableLiveTransport` transparent rotation/replay with bounds and an
  optional observer (`RunConfig.liveRecoveryObserver`, `LiveRecoveryEvent`);
- Live flow cancel/late-open cleanup (`doOnCancel` disposes the sender and
  closes the connection).

GenerateContent and context-cache behavior comes from upstream, not a Virtis
patch. Context caching is not Live session resumption, and the Live transport
never invokes GenerateContent cache management. The public `GeminiLiveTransport`
seam, SDK protocol, provider authentication and model binding remain
authoritative.

## Retained recovery, compression and GoAway behavior

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

Focused gate on the `v1.14.0` port (results recorded in the handoff; counts quoted
below in this section are historical `v1.12.0` evidence):

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


## Patch inventory versus `v1.14.0`

| Patch | Production boundary | Regression coverage |
| --- | --- | --- |
| Live session-resumption configuration | `agents/RunConfig.java`, `flows/llmflows/Basic.java` | `RunConfigTest`; `BasicTest` |
| Provider resumption updates | `models/GeminiLlmConnection.java`, `models/LlmResponse.java`, `events/Event.java`, `flows/llmflows/BaseLlmFlow.java` | `GeminiLlmConnectionTest`; `LlmResponseTest`; `EventTest`; `RunnerTest.runLive_contentlessSessionResumptionUpdatePropagatesToEvent` |
| Gemini 3.x Live input shapes | `models/Gemini.java`, `models/GeminiLlmConnection.java` | `GeminiLlmConnectionTest` model-specific text, audio, video, Gemini 2.5 media, and incomplete (`partial=true`) system-content cases |
| Safe Live diagnostics | `models/GeminiLlmConnection.java`, `models/Gemini.java`, `flows/llmflows/BaseLlmFlow.java` | `GeminiLlmConnectionTest.diagnosticMessageTypes_reportsPresenceWithoutPayloadOrHandle` |
| Typed compression | `agents/RunConfig.java`, `flows/llmflows/Basic.java` | `RunConfigTest`; `BasicTest` (real SDK setup serializer) |
| Typed GoAway | `models/LlmResponse.java`, `events/Event.java`, `models/GeminiLlmConnection.java`, `flows/llmflows/BaseLlmFlow.java` | `LlmResponseTest`; `EventTest`; `GeminiLlmConnectionTest`; `RunnerTest` |
| Transparent rotation/replay and observer | `models/ResumableLiveTransport.java`, `models/Gemini.java`, `models/LlmRequest.java`, `agents/RunConfig.java`, `flows/llmflows/Basic.java` | `ResumableLiveTransportTest`; `LlmRequestTest`; `RunConfigTest`; `BasicTest` |
| Cancel/late-open cleanup | `flows/llmflows/BaseLlmFlow.java`, `models/GeminiLlmConnection.java` | `RunnerTest.runLive_cancellationClosesConnectionAndStopsSender`; `GeminiLlmConnectionTest` late close |

Dropped (now upstream): explicit queued-content turn completion. Replaced by
upstream `partial`; coverage is upstream `LiveRequestQueueTest`,
`GeminiLlmConnectionTest.sendContent_*` partial cases and
`RunnerTest.runLive_incompleteContentPropagatesPartialToConnection`.

### Upstream status of remaining patches (checked at `v1.14.0`)

None of the retained patches has an upstream equivalent at `v1.14.0`: no
`sessionResumption`/compression propagation in `Basic`, no
`liveSessionResumptionUpdate`/`liveGoAway` fields or conversion, no model-name
handoff or split audio/video routing, setup/message diagnostics still need
sanitizing, no replay helper, and no Live-flow `doOnCancel` cleanup. Keep each
patch until upstream provides equivalent behavior and the matching regression
passes without the fork change.

### Behavioral notes

`LiveRequestQueue.content(content, true)` marks incomplete content (`partial`);
`BaseLlmFlow` forwards `request.isPartial()` to `sendContent(content, partial)`,
which sends client content with `turnComplete(!partial)`. The one-argument API
remains turn-complete. Function-response-only content is always a tool response.

`RunConfig.sessionResumption(...)` carries a persisted provider handle through
builder copies and `Basic` into `LiveConnectConfig`. Incoming provider updates
become `LlmResponse.liveSessionResumptionUpdate` and then
`Event.liveSessionResumptionUpdate`, including updates with no content.

Resolved Gemini 3.1 Live models send ordinary all-text, non-system content
through the realtime text field (independent of `partial`). Resolved Live Gemini
3.x models map audio to realtime audio and image/video to realtime video. Gemini
3.8 ordinary text and all Gemini 2.5 realtime blobs keep client-content/media.
System-role content stays on client-content so `partial=true` remains
expressible. The split-media predicate evaluates the resolved model basename, so
qualified resource names work without classifying non-Live or `gemini-30` names
as Gemini 3.x Live.

Live diagnostics derive only a fixed list of present message kinds and never
serialize a `LiveServerMessage`, provider body, media bytes, or resumption handle.

## Upgrade and removal rule

For a later upstream base, compare every inventoried end-to-end boundary and
remove a patch only when upstream provides equivalent behavior and the
corresponding regression passes without the fork change. A similarly named
continuation, pause/resume, or context-cache API is not equivalent proof of
provider Live session resumption.

## Changed-file appendix (`git diff --name-status v1.14.0..HEAD`)

| Path | Change / proof responsibility |
| --- | --- |
| `VIRTIS_LIVE_PATCH.md` | Maintainer inventory. |
| `core/src/main/java/com/google/adk/agents/RunConfig.java` | Typed resumption, compression, replay bounds, observer and `LiveRecoveryEvent`. |
| `core/src/main/java/com/google/adk/events/Event.java` | Typed resumption/GoAway, JSON, copies, equality and hash. |
| `core/src/main/java/com/google/adk/flows/llmflows/BaseLlmFlow.java` | Contentless lifecycle conversion, safe logging, cancellation cleanup. |
| `core/src/main/java/com/google/adk/flows/llmflows/Basic.java` | Resumption/compression setup and replay-bound/observer propagation. |
| `core/src/main/java/com/google/adk/models/Gemini.java` | Resolved model handed to connection, safe setup logging, transparent-only replay composition. |
| `core/src/main/java/com/google/adk/models/GeminiLlmConnection.java` | Model-specific media/text, lifecycle conversion, safe diagnostics, late-open cleanup. |
| `core/src/main/java/com/google/adk/models/LlmRequest.java` | Nonserialized replay bounds and observer. |
| `core/src/main/java/com/google/adk/models/LlmResponse.java` | Typed resumption/GoAway fields and builders. |
| `core/src/main/java/com/google/adk/models/ResumableLiveTransport.java` | Checkpoint/window/handoff authority with safe failures and observation. |
| `core/src/test/java/.../agents/RunConfigTest.java` | Typed setup, compression, observer copies and bound validation. |
| `core/src/test/java/.../events/EventTest.java` | Lifecycle JSON/copies/equality. |
| `core/src/test/java/.../flows/llmflows/BasicTest.java` | Setup/observer propagation and real SDK serializer. |
| `core/src/test/java/.../models/GeminiLlmConnectionTest.java` | Media/text routing (partial polarity), resumption, diagnostics, GoAway, late close. |
| `core/src/test/java/.../models/LlmRequestTest.java` | Bounds/observer defaults, copies, JSON exclusion. |
| `core/src/test/java/.../models/LlmResponseTest.java` | Lifecycle serialization/copies. |
| `core/src/test/java/.../models/ResumableLiveTransportTest.java` | Replay, bounds, indices, ambiguity, cancellation, observer semantics. |
| `core/src/test/java/.../runner/RunnerTest.java` | Partial propagation, contentless resumption through real flow, cancellation stops sender. |
