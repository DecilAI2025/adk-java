# Virtis Live runtime patch

This fork lane is based on exact upstream `google/adk-java` commit
`923d602e51aea8be4811676afb57d6264f4fe877`. That base contains the upstream
context-cache change from PR `#1626`, commit
`68914c59e254e50fece01711d5213d3c03b07090`.

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

## Verification

Focused and complete local gates are:

```sh
./mvnw -pl core -Dtest=ContextCacheConfigTest,ContextCacheRequestProcessorTest,GeminiContextCacheManagerTest,LiveRequestQueueTest,RunConfigTest,EventTest,BasicTest,GeminiLlmConnectionTest,LlmResponseTest,RunnerTest test
env -u OPENAI_API_KEY -u ANTHROPIC_API_KEY -u GOOGLE_API_KEY -u GEMINI_API_KEY ./mvnw test
```

Both gates passed on 2026-10-14. The focused gate completed with `BUILD
SUCCESS`; its primary selected suite ran 273 tests with zero failures, errors,
or skips. The complete gate completed all 19 reactor modules with `BUILD
SUCCESS` in 1:45; the core module's primary suite ran 2,359 tests with zero
failures or errors and 24 skips. Provider API keys were removed from the
complete-gate environment, so no live provider action was attempted.

Only the reviewed commit containing this inventory may be published and
consumed through the immutable JitPack coordinates
`com.github.DecilAI2025.adk-java:google-adk:<full-commit-sha>`. The publication
handoff records that commit's full SHA and resolution proof after Git creates
the commit object. A branch name, moving reference, tag, or local Maven
artifact is not an acceptable dependency or rollback/forward mechanism.

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
session resumption.
