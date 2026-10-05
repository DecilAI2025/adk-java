# Virtis Live runtime patch

This branch is based on official ADK Java v1.11.0, commit
`c8b1de6a1878ae2ac1c66d1e05fc30fc85b060b4`.

It ports the Live patch from the published Virtis 1.7.1-based fork
`bc165af5c7faeb96d54d4f67b7c56276e8b640d4`:

- Explicit `LiveRequest.turnComplete`, queued content with a completion flag,
  and propagation through `BaseLlmFlow` to the connection.
- `RunConfig.sessionResumption` and propagation into Live connection settings.
- Provider resumption updates carried through `LlmResponse`, `Event`, builders,
  copies and the flow's event conversion, including updates without content.
- Gemini 3.1 Live realtime text/audio/video input mapping while retaining
  the 2.5 input path and hidden system instructions as incomplete client content.

Live message diagnostics omit provider bodies and opaque resumption handles.

The upstream `GeminiLiveTransport` boundary is preserved. `Gemini` supplies its
resolved model name to `GeminiLlmConnection`; the existing package-private
transport-only constructor remains available for upstream tests.
The port removes the old fork's special `AsyncSession` test constructor and
tests the transport boundary directly instead.

## Verification

```sh
env -u OPENAI_API_KEY -u ANTHROPIC_API_KEY -u GOOGLE_API_KEY -u GEMINI_API_KEY ./mvnw test
```

On 2026-10-05, all 18 reactor modules passed: 2,779 tests, zero failures/errors,
27 skipped. API-key-gated external-provider tests were not run by this gate.
Focused Live patch tests also passed. Backend ADC evidence is maintained in
`virtis_gemini_agents_backend_j/reference/docs/adk-live-runtime-control-and-gemini31-findings.md`.

Publish only a reviewed committed revision and pin the backend to its immutable
JitPack commit SHA. A local test artifact is not publication or deployment proof.

## Patch inventory versus upstream v1.11.0

The comparison is against the exact upstream base above, not against a moving
`main`. Upstream v1.11.0 does not expose the first four capabilities below in
the form the Virtis runtime consumes. No backend storage or business policy is
implemented inside this fork.

| Patch | Why Virtis needs it | Production files under `core/src/main/java/com/google/adk/` | Regression coverage |
| --- | --- | --- | --- |
| Explicit queued-content turn completion | A backend-originated hidden system instruction must not trigger a user turn. Preserve `turnComplete=false` until the Live transport; ordinary content keeps the existing true default. | `agents/LiveRequest.java`, `agents/LiveRequestQueue.java`, `models/BaseLlmConnection.java`, `flows/llmflows/BaseLlmFlow.java`, `models/GeminiLlmConnection.java` | `LiveRequestQueueTest`, `RunnerTest.runLive_contentWithTurnCompleteFalsePropagatesToConnection`, `GeminiLlmConnectionTest` |
| Live session-resumption configuration | A reconnect must offer the persisted provider handle instead of starting a fresh provider conversation. The configuration must survive builder copies and flow preparation. | `agents/RunConfig.java`, `flows/llmflows/Basic.java` | `RunConfigTest`, `BasicTest` |
| Provider resumption updates | The backend needs the provider's new handle and consumed-message index, including updates containing no text/audio. Preserve the update through responses, event conversion, builders, equality and copies. | `models/GeminiLlmConnection.java`, `models/LlmResponse.java`, `events/Event.java`, `flows/llmflows/BaseLlmFlow.java` | `GeminiLlmConnectionTest`, `LlmResponseTest`, `EventTest`, runner fixture `testing/TestLlm.java` |
| Gemini 3.1 Live input shapes | Newer Live models require realtime text/audio/video fields. Select them using the resolved model name, preserve the 2.5 media behavior, and keep incomplete system instructions on client-content transport. Function-response handling stays intact. | `models/Gemini.java`, `models/GeminiLlmConnection.java` | `GeminiLlmConnectionTest` covers model-specific text/media mapping and hidden controls |
| Bounded Live message diagnostics | Provider message bodies can contain governed content or opaque resume credentials. The received/unknown-message and resumption-update diagnostics report presence/type without dumping these payloads. | `models/GeminiLlmConnection.java` | Source review; the Live behavior regressions above still pass |

### Examples

A hidden control can be queued with `queue.content(systemInstruction, false)`.
The queue's explicit flag reaches `sendContent(content, false)` rather than
being silently replaced by `true`. This allows the control to affect the next
user response without independently closing a turn.

For reconnect, the backend selects `RunConfig.sessionResumption(...)` with an
already persisted handle. Incoming provider updates become
`LlmResponse.liveSessionResumptionUpdate` and then
`Event.liveSessionResumptionUpdate`. An update without content must still reach
the backend's dedicated resumption-state owner. It is not user-visible text and
must not be copied into ordinary event or conversation storage.

### Adaptation to upstream 1.11.0

The old fork attached tests directly to an SDK `AsyncSession`. Upstream 1.11.0
introduced `GeminiLiveTransport`; the port retains that interface, asynchronous
transport future and existing transport lifetime. Tests now mock the upstream
transport boundary instead of retaining a test-only SDK constructor.
`Gemini` passes its resolved model name into the connection. The existing
transport-only package constructor remains for upstream tests.

Upstream's agent pause/resume behavior concerns invocation continuation. It is
not equivalent to supplying and persisting a provider Live websocket handle.
The fork does not replace upstream pause/resume behavior.

## Backend-owned companion behavior

These are maintained in `virtis_gemini_agents_backend_j`, not ADK:

- `AssistantTransferBarrier` waits for the runner's durable append acknowledgement
  before a transferred assistant consumes history. Upstream's transfer flow alone
  does not establish that PostgreSQL persistence barrier.
- `EventScrubber` removes opaque resumption updates from generic event/response
  sinks. The dedicated resume-state table owns those handles.
- `AdkEventStorage` applies the admitted encrypted persistence policy to complete
  ADK events and inline media and restores authorized history. Native Live
  transcript rows store encrypted envelopes and safe placeholders. Event and
  transcript writes retain one transaction and deterministic replay checks.
- Platform admission, immutable run policy, service identity, handoff credentials
  and bounded ordered Live writes remain backend responsibilities.

The ADC regression in the backend exercises real Vertex
`gemini-live-2.5-flash-native-audio`: hidden control, remembered content across
reconnect and encrypted storage exclusions. Gemini 3.1-specific input shapes are
covered by transport tests; this does not certify a real 3.1 provider deployment.
See the backend findings document for the released pin and exact test results.

## Future upgrades and patch removal

For each new official tag, compare its source at the queue, RunConfig, response,
event and transport boundaries above. Remove a patch only when upstream carries
the equivalent behavior end to end and the regression for that behavior passes
without it. A similarly named pause/resume API or a dependency version bump is
not sufficient evidence.

Retain upstream transport and lifecycle fixes when rebasing. Run the complete
fork reactor and backend `./mvnw verify`, then the opt-in ADC reconnect regression
with synthetic data and isolated storage. Publish the fork revision and resolve
its actual JitPack artifact before pinning the backend to the immutable commit.
Never replace a published commit's contents or use a local-only coordinate as a
release dependency.
