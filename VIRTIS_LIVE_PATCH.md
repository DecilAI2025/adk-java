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
