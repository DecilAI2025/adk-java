# Virtis Live runtime patch

This fork lane is based on the exact upstream `google/adk-java` `v1.12.0`
release-tag commit `4f2178cce329aa2ce7ffb94e2be399f5dfd370c2`. That release
contains the upstream context-cache change from PR `#1626`, commit
`68914c59e254e50fece01711d5213d3c03b07090`, plus the complete published
`v1.12.0` release history. The human-readable branch is
`runtime-control-v1.12.0`; consumers must use the immutable code commit below,
not the branch name.

The lane ports only the five Live capabilities retained by the frozen Virtis
runtime contract:

- explicit queued-content `turnComplete`, including `false` for incomplete
  hidden instructions;
- `RunConfig.sessionResumption` propagation into `LiveConnectConfig`;
- provider resumption updates carried through `LlmResponse`, `Event`, builders,
  copies, and contentless event conversion;
- Gemini 3.1 Live realtime text/audio/video mapping while Gemini 2.5 retains
  its media behavior and incomplete system instructions remain client content;
- bounded Live diagnostics that contain only safe message-type presence, never
  provider bodies or opaque resumption handles.

The upstream `GeminiLiveTransport`, GenerateContent, and context-cache
implementations remain authoritative. Live WebSocket transport does not invoke
GenerateContent context-cache management.

## Runtime and ownership boundary

This repository remains an ADK library, not a product control plane. The
reviewed fork does not contain Virtis tenants, scoring policy, evidence
authority, agent installations, provider-route policy, PII decisions, budgets,
or business outcomes.

| Concern | ADK and this fork | Gemini Backend | Platform |
| --- | --- | --- | --- |
| Agent execution | Runs upstream agent graphs, sessions, tools, structured input/output schemas, and subagents | Validates the governed runtime request and invokes ADK | Selects product policy and owns the durable product workflow |
| Evidence and multimodal input | Carries standard GenAI `Content` and `Part` values; the Live patch maps Gemini 3.1 realtime text/audio/video without changing Gemini 2.5 media behavior | Resolves authorized artifacts and constructs provider-safe ADK inputs | Owns canonical messages, artifacts, citations, retention, and access decisions |
| Context cache | Owns optional Gemini GenerateContent cache fingerprints, resources, reuse, expiry, and pre-dispatch fallback | Enables or omits `ContextCacheConfig` only after its authorization and policy checks | Publishes only the governed cache on/off selection; it does not supply a cache key, prefix, or provider resource |
| Results | Returns model events, schema-checked agent output, and observed provider usage | Persists the governed runtime result and safe execution receipts | Owns final scores and other business decisions |

The structured schemas, session services, and sequential, parallel, loop, and
LLM subagent composition in this repository are upstream ADK capabilities. The
Virtis patch neither creates a second Platform conversation store nor makes an
ADK session or cache a source of canonical evidence. Each application remains
responsible for choosing and isolating its session service.

### Context-cache restrictions

Context caching is opt-in through `App.contextCacheConfig`; without that config,
ADK does not create or reference a cache. It applies only to Gemini
`generateContent`, not Live WebSocket connections or non-Gemini model adapters.

A cached prefix can contain the exact system instruction, tools, tool config,
and leading contents. Its fingerprint also binds the model and Gemini API or
Vertex backend (including Vertex project and location). When a cached resource
is referenced, those cached instruction and tool fields are not resent on the
GenerateContent request because the provider treats the cached values as
authoritative. ADK therefore does not promise reuse across different agents,
instructions, tools, backends, or sessions, and it never weakens an instruction
or unions tool authority to obtain a hit.

Cache eligibility and hits are optimizations, not execution authority. An
ineligible or changed prefix runs uncached. A cache-create failure before model
dispatch falls back to the same uncached logical request. A cache-delete failure
does not block refresh, while thread interruption still propagates. Only
provider-reported cached-input token usage proves a hit. This library does not
estimate prices or convert usage to USD for cache selection.

Provider credentials are supplied by the embedding application through the
normal Gemini API or Vertex client configuration. They are not stored in this
fork, cache metadata, session events, or diagnostics.

## Verification

Focused and complete local gates are:

```sh
./mvnw -pl core -Dtest=ContextCacheConfigTest,ContextCacheRequestProcessorTest,GeminiContextCacheManagerTest,LiveRequestQueueTest,RunConfigTest,EventTest,BasicTest,GeminiLlmConnectionTest,LlmResponseTest,RunnerTest test
env -u OPENAI_API_KEY -u ANTHROPIC_API_KEY -u GOOGLE_API_KEY -u GEMINI_API_KEY ./mvnw test
```

Both gates passed again on 2026-10-18 for immutable code commit
`a671d98eb7731d6e73accc9ddcaf47f1cedad399` on the `v1.12.0` line. The focused
command completed with `BUILD SUCCESS`; its primary selected suite ran 273
tests with zero failures or errors, and the additional configured executions
were green with one environment-dependent skip. The complete command completed
all 19 reactor modules with `BUILD SUCCESS`. The commands ran with external
provider API keys, Google application credentials, and Google project/location
variables unset; no ADC, cloud, or live provider action was invoked.

The reviewed runtime artifact remains immutable commit
`a671d98eb7731d6e73accc9ddcaf47f1cedad399`. It may be consumed through the
JitPack coordinates
`com.github.DecilAI2025.adk-java:google-adk:a671d98eb7731d6e73accc9ddcaf47f1cedad399`.
A branch name, moving reference, tag, or local Maven artifact is not an
acceptable dependency or rollback/forward mechanism. A later fork artifact
requires its own reviewed immutable commit and resolution proof.

## Patch inventory versus exact upstream base

| Patch | Production boundary | Regression coverage |
| --- | --- | --- |
| Explicit queued-content turn completion | `agents/LiveRequest.java`, `agents/LiveRequestQueue.java`, `models/BaseLlmConnection.java`, `flows/llmflows/BaseLlmFlow.java`, `models/GeminiLlmConnection.java` | `LiveRequestQueueTest`; `RunnerTest.runLive_contentWithTurnCompleteFalsePropagatesToConnection`; `GeminiLlmConnectionTest` |
| Live session-resumption configuration | `agents/RunConfig.java`, `flows/llmflows/Basic.java` | `RunConfigTest`; `BasicTest` |
| Provider resumption updates | `models/GeminiLlmConnection.java`, `models/LlmResponse.java`, `events/Event.java`, `flows/llmflows/BaseLlmFlow.java` | `GeminiLlmConnectionTest`; `LlmResponseTest`; `EventTest`; `RunnerTest.runLive_contentlessSessionResumptionUpdatePropagatesToEvent` |
| Gemini 3.1 Live input shapes | `models/Gemini.java`, `models/GeminiLlmConnection.java` | `GeminiLlmConnectionTest` model-specific text, audio, video, Gemini 2.5 media, and incomplete-system-content cases |
| Safe Live diagnostics | `models/GeminiLlmConnection.java` | `GeminiLlmConnectionTest.diagnosticMessageTypes_reportsPresenceWithoutPayloadOrHandle` |

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

For a later upstream base, compare all five end-to-end boundaries and remove a
patch only when upstream provides equivalent behavior and the corresponding
regression passes without the fork change. A similarly named continuation,
pause/resume, or context-cache API is not equivalent proof of provider Live
session resumption. Do not advance this line to upstream `main` or silently
retarget `runtime-control-v1.12.0`; create a separately named version line from
the exact future release-tag commit and repin consumers only after its focused,
full-reactor, and immutable-artifact resolution gates pass.
