/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerGoAway;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSessionResumptionUpdate;
import com.google.genai.types.LiveServerSetupComplete;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.LiveServerToolCallCancellation;
import com.google.genai.types.Part;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class GeminiLlmConnectionTest {

  @Test
  public void sendContent_withGemini31LiveText_usesRealtimeTextInput() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.1-flash-live");

    connection.sendContent(Content.fromParts(Part.fromText("hello 3.1")), true).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().text()).hasValue("hello 3.1");
    verify(transport, never()).sendClientContent(any());
  }

  @Test
  public void sendContent_withGemini31LiveIncompleteSystemContent_keepsClientContentPath() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendClientContent(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.1-flash-live");
    Content systemContent =
        Content.builder()
            .role("system")
            .parts(ImmutableList.of(Part.fromText("hidden runtime control")))
            .build();

    connection.sendContent(systemContent, false).blockingAwait();

    ArgumentCaptor<LiveSendClientContentParameters> clientContentCaptor =
        ArgumentCaptor.forClass(LiveSendClientContentParameters.class);
    verify(transport).sendClientContent(clientContentCaptor.capture());
    assertThat(clientContentCaptor.getValue().turns().get()).containsExactly(systemContent);
    assertThat(clientContentCaptor.getValue().turnComplete()).hasValue(false);
    verify(transport, never()).sendRealtimeInput(any());
  }

  @Test
  public void sendContent_withGemini25LiveText_keepsClientContentPath() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendClientContent(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-2.5-flash-live");
    Content content = Content.fromParts(Part.fromText("hello 2.5"));

    connection.sendContent(content, true).blockingAwait();

    ArgumentCaptor<LiveSendClientContentParameters> clientContentCaptor =
        ArgumentCaptor.forClass(LiveSendClientContentParameters.class);
    verify(transport).sendClientContent(clientContentCaptor.capture());
    assertThat(clientContentCaptor.getValue().turns().get()).containsExactly(content);
    assertThat(clientContentCaptor.getValue().turnComplete()).hasValue(true);
    verify(transport, never()).sendRealtimeInput(any());
  }

  @Test
  public void sendContent_withGemini38LiveText_keepsClientContentPath() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendClientContent(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.8-live");
    Content content = Content.fromParts(Part.fromText("hello 3.8"));

    connection.sendContent(content, true).blockingAwait();

    ArgumentCaptor<LiveSendClientContentParameters> clientContentCaptor =
        ArgumentCaptor.forClass(LiveSendClientContentParameters.class);
    verify(transport).sendClientContent(clientContentCaptor.capture());
    assertThat(clientContentCaptor.getValue().turns().get()).containsExactly(content);
    assertThat(clientContentCaptor.getValue().turnComplete()).hasValue(true);
    verify(transport, never()).sendRealtimeInput(any());
  }

  @Test
  public void sendRealtime_withGemini31LiveAudio_usesAudioField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.1-flash-live");
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=16000").data(new byte[] {1}).build();

    connection.sendRealtime(audio).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().audio()).hasValue(audio);
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini31LiveVideo_usesVideoField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.1-flash-live");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().video()).hasValue(video);
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini38LiveAudio_usesAudioField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.8-live");
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=16000").data(new byte[] {1}).build();

    connection.sendRealtime(audio).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().audio()).hasValue(audio);
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini38LiveVideo_usesVideoField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.8-live");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().video()).hasValue(video);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
  }

  @Test
  public void sendRealtime_withQualifiedGemini38LiveVideo_usesVideoField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection =
        connectionForTransport(
            transport,
            "projects/test/locations/us-central1/publishers/google/models/gemini-3.8-live");
    Blob video = Blob.builder().mimeType("video/webm").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().video()).hasValue(video);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini25LiveAudio_keepsMediaField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-2.5-flash-live");
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=24000").data(new byte[] {1}).build();

    connection.sendRealtime(audio).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(audio);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini25LiveVideo_keepsMediaField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-2.5-flash-live");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(video);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  @Test
  public void sendRealtime_withNonLiveGemini38Video_keepsMediaField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.8-flash");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(video);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  @Test
  public void sendRealtime_withUnknownLiveModelVideo_keepsMediaField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "custom-video-live");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(video);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini30LiveVideo_keepsMediaField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-30-live");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(video);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini38LiveUnknownMime_keepsMediaField() {
    GeminiLiveTransport transport = mock(GeminiLiveTransport.class);
    when(transport.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForTransport(transport, "gemini-3.8-live");
    Blob opaque = Blob.builder().mimeType("application/octet-stream").data(new byte[] {1}).build();

    connection.sendRealtime(opaque).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(transport).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(opaque);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
    assertThat(realtimeCaptor.getValue().video()).isEmpty();
  }

  private static GeminiLlmConnection connectionForTransport(
      GeminiLiveTransport transport, String modelName) {
    when(transport.receive(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    return new GeminiLlmConnection(CompletableFuture.completedFuture(transport), modelName);
  }

  @Test
  public void convertToServerResponse_withInterruptedTrue_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Model response")))
            .turnComplete(false)
            .interrupted(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();
    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);

    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Model response");
    assertThat(response.partial()).hasValue(true);
    assertThat(response.turnComplete()).hasValue(false);
    assertThat(response.interrupted()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withInterruptedFalse_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Continuing response")))
            .turnComplete(false)
            .interrupted(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.interrupted()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withoutInterruptedField_mapsEmptyOptional() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Normal response")))
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.interrupted()).isEmpty();
    assertThat(response.turnComplete()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withTurnCompleteTrue_mapsPartialFalse() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Final response")))
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withTurnCompleteFalse_mapsPartialTrue() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Partial response")))
            .turnComplete(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.partial()).hasValue(true);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withToolCall_mapsContentWithFunctionCall() {
    FunctionCall functionCall = FunctionCall.builder().name("tool").build();
    LiveServerToolCall toolCall =
        LiveServerToolCall.builder().functionCalls(ImmutableList.of(functionCall)).build();

    LiveServerMessage message = LiveServerMessage.builder().toolCall(toolCall).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(functionCall);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withMultipleFunctionCalls_preservesAllCallsAsParts() {
    // Regression test for parallel tool calling on the live/BIDI path: a single toolCall message
    // can carry multiple FunctionCalls, and all of them must be preserved (not just the last).
    FunctionCall getWeather =
        FunctionCall.builder().name("getWeather").args(ImmutableMap.of("city", "Paris")).build();
    FunctionCall getTime =
        FunctionCall.builder()
            .name("getTime")
            .args(ImmutableMap.of("timezone", "Europe/London"))
            .build();
    LiveServerToolCall toolCall =
        LiveServerToolCall.builder().functionCalls(ImmutableList.of(getWeather, getTime)).build();

    LiveServerMessage message = LiveServerMessage.builder().toolCall(toolCall).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(2);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(getWeather);
    assertThat(response.content().get().parts().get().get(1).functionCall()).hasValue(getTime);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withUsageMetadata_mapsGenerateResponseUsageMetadata() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .usageMetadata(
                UsageMetadata.builder()
                    .promptTokenCount(10)
                    .responseTokenCount(20)
                    .totalTokenCount(30)
                    .build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);
    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.usageMetadata()).isPresent();
    GenerateContentResponseUsageMetadata expectedUsageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    assertThat(response.usageMetadata()).hasValue(expectedUsageMetadata);
  }

  @Test
  public void convertToServerResponse_withToolCallCancellation_returnsNoValues() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .toolCallCancellation(LiveServerToolCallCancellation.builder().build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);
    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withSetupComplete_returnsNoValues() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .setupComplete(LiveServerSetupComplete.builder().build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withSessionResumptionUpdate_mapsContentlessUpdate() {
    LiveServerSessionResumptionUpdate update =
        LiveServerSessionResumptionUpdate.builder()
            .newHandle("opaque-resume-handle")
            .resumable(true)
            .lastConsumedClientMessageIndex(17L)
            .build();
    LiveServerMessage message = LiveServerMessage.builder().sessionResumptionUpdate(update).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();
    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    assertThat(testObserver.values().get(0).content()).isEmpty();
    assertThat(testObserver.values().get(0).liveSessionResumptionUpdate()).hasValue(update);
  }

  @Test
  public void diagnosticMessageTypes_reportsPresenceWithoutPayloadOrHandle() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .serverContent(
                LiveServerContent.builder()
                    .modelTurn(Content.fromParts(Part.fromText("governed-provider-body")))
                    .build())
            .sessionResumptionUpdate(
                LiveServerSessionResumptionUpdate.builder()
                    .newHandle("opaque-resume-handle")
                    .build())
            .build();

    ImmutableList<String> diagnosticTypes = GeminiLlmConnection.diagnosticMessageTypes(message);

    assertThat(diagnosticTypes).containsExactly("server_content", "session_resumption_update");
    assertThat(diagnosticTypes.toString()).doesNotContain("governed-provider-body");
    assertThat(diagnosticTypes.toString()).doesNotContain("opaque-resume-handle");
  }

  @Test
  public void convertToServerResponse_withUnknownMessage_returnsErrorResponse() {
    LiveServerMessage message = LiveServerMessage.builder().build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.errorCode()).isPresent();
    assertThat(response.errorMessage()).hasValue("Received unknown server message.");
  }

  @Test
  public void convertToServerResponse_withContentAndUsageMetadata_emitsMultiple() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Model response")))
            .turnComplete(true)
            .build();

    UsageMetadata usageMetadata =
        UsageMetadata.builder()
            .promptTokenCount(10)
            .responseTokenCount(20)
            .totalTokenCount(30)
            .build();

    LiveServerMessage message =
        LiveServerMessage.builder()
            .serverContent(serverContent)
            .usageMetadata(usageMetadata)
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(2);
    testObserver.assertComplete();

    List<LlmResponse> responses = testObserver.values();

    // Check for ServerContent response
    LlmResponse contentResponse = responses.get(0);
    assertThat(contentResponse.content()).isPresent();
    assertThat(contentResponse.content().get().text()).isEqualTo("Model response");
    assertThat(contentResponse.usageMetadata()).isEmpty();

    // Check for UsageMetadata response
    LlmResponse usageResponse = responses.get(1);
    assertThat(usageResponse.content()).isEmpty();
    assertThat(usageResponse.usageMetadata()).isPresent();
    GenerateContentResponseUsageMetadata expectedUsageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    assertThat(usageResponse.usageMetadata()).hasValue(expectedUsageMetadata);
  }

  @Test
  public void receive_completesWhenTransportSignalsStreamEnd() {
    // A transport that ends its own receive stream completes the connection's response stream, so a
    // live run with no client close still terminates.
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));

    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);

    subscriber.assertComplete();
    subscriber.assertNoErrors();
  }

  @Test
  public void convertToServerResponse_goAwayIsTypedAndNotAnUnknownMessage() {
    LiveServerGoAway goAway = LiveServerGoAway.builder().build();
    LiveServerMessage message = LiveServerMessage.builder().goAway(goAway).build();

    TestObserver<LlmResponse> subscriber =
        GeminiLlmConnection.convertToServerResponse(message).test();

    subscriber.assertComplete().assertValueCount(1).assertNoErrors();
    assertThat(subscriber.values().get(0).liveGoAway()).hasValue(goAway);
    assertThat(subscriber.values().get(0).errorCode()).isEmpty();
    assertThat(GeminiLlmConnection.diagnosticMessageTypes(message)).containsExactly("go_away");
  }

  @Test
  public void close_unwrapsSafeRecoveryFailureFromFutureWrappers() {
    CompletableFuture<GeminiLiveTransport> opening = new CompletableFuture<>();
    GeminiLlmConnection connection = new GeminiLlmConnection(opening);
    TestSubscriber<LlmResponse> subscriber = connection.receive().test();

    connection.close(
        new ExecutionException(
            new CompletionException(
                new IllegalStateException(ResumableLiveTransport.DELIVERY_UNKNOWN))));

    subscriber.assertError(
        error ->
            error instanceof IllegalStateException
                && error.getMessage().equals(ResumableLiveTransport.DELIVERY_UNKNOWN)
                && error.getCause() == null);
  }

  @Test
  public void close_beforeTransportConnectsClosesLateTransport() throws Exception {
    CompletableFuture<GeminiLiveTransport> opening = new CompletableFuture<>();
    GeminiLlmConnection connection = new GeminiLlmConnection(opening);
    connection.close();
    FakeTransport transport = new FakeTransport(false);

    opening.complete(transport);

    // Join the asynchronous setup callback through a send; a closed connection never sends data.
    connection.sendHistory(List.of()).onErrorComplete().blockingAwait();
    assertThat(transport.closeCount.get()).isEqualTo(1);
  }

  @Test
  public void close_afterTransportStreamEnd_isNoOp() {
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));
    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertComplete();

    connection.close(); // Already completed via stream-end; must not re-terminate or error.

    subscriber.assertComplete();
    subscriber.assertNoErrors();
    // completeReceive leaves the transport to close itself, so no close() is issued on this path.
    assertThat(transport.closeCount.get()).isEqualTo(0);
  }

  /**
   * A minimal in-process {@link GeminiLiveTransport} that records closes and can end its stream.
   */
  private static final class FakeTransport implements GeminiLiveTransport {
    private final boolean endStreamOnReceive;
    final AtomicInteger closeCount = new AtomicInteger();

    FakeTransport(boolean endStreamOnReceive) {
      this.endStreamOnReceive = endStreamOnReceive;
    }

    @Override
    public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> receive(
        Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
      if (endStreamOnReceive) {
        onStreamEnd.run();
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
      closeCount.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }
  }
}
