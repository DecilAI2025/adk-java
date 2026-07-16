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
import com.google.genai.AsyncSession;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSessionResumptionUpdate;
import com.google.genai.types.LiveServerSetupComplete;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.LiveServerToolCallCancellation;
import com.google.genai.types.Part;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class GeminiLlmConnectionTest {

  @Test
  public void sendContent_withGemini31LiveText_usesRealtimeTextInput() {
    AsyncSession session = mock(AsyncSession.class);
    when(session.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForSession(session, "gemini-3.1-flash-live-preview");

    connection.sendContent(Content.fromParts(Part.fromText("hello 3.1")), true).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(session).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().text()).hasValue("hello 3.1");
    verify(session, never()).sendClientContent(any());
  }

  @Test
  public void sendContent_withGemini31LiveSystemContent_keepsClientContentPath() {
    AsyncSession session = mock(AsyncSession.class);
    when(session.sendClientContent(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForSession(session, "gemini-3.1-flash-live-preview");
    Content systemContent =
        Content.builder()
            .role("system")
            .parts(ImmutableList.of(Part.fromText("hidden runtime control")))
            .build();

    connection.sendContent(systemContent, false).blockingAwait();

    ArgumentCaptor<LiveSendClientContentParameters> clientContentCaptor =
        ArgumentCaptor.forClass(LiveSendClientContentParameters.class);
    verify(session).sendClientContent(clientContentCaptor.capture());
    assertThat(clientContentCaptor.getValue().turns().get()).containsExactly(systemContent);
    assertThat(clientContentCaptor.getValue().turnComplete()).hasValue(false);
    verify(session, never()).sendRealtimeInput(any());
  }

  @Test
  public void sendContent_withGemini25LiveText_keepsClientContentPath() {
    AsyncSession session = mock(AsyncSession.class);
    when(session.sendClientContent(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForSession(session, "gemini-2.5-flash-live-preview");
    Content content = Content.fromParts(Part.fromText("hello 2.5"));

    connection.sendContent(content, true).blockingAwait();

    ArgumentCaptor<LiveSendClientContentParameters> clientContentCaptor =
        ArgumentCaptor.forClass(LiveSendClientContentParameters.class);
    verify(session).sendClientContent(clientContentCaptor.capture());
    assertThat(clientContentCaptor.getValue().turns().get()).containsExactly(content);
    assertThat(clientContentCaptor.getValue().turnComplete()).hasValue(true);
    verify(session, never()).sendRealtimeInput(any());
  }

  @Test
  public void sendRealtime_withGemini31LiveAudio_usesAudioField() {
    AsyncSession session = mock(AsyncSession.class);
    when(session.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForSession(session, "gemini-3.1-flash-live-preview");
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=16000").data(new byte[] {1}).build();

    connection.sendRealtime(audio).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(session).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().audio()).hasValue(audio);
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini31LiveVideo_usesVideoField() {
    AsyncSession session = mock(AsyncSession.class);
    when(session.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForSession(session, "gemini-3.1-flash-live-preview");
    Blob video = Blob.builder().mimeType("image/jpeg").data(new byte[] {1}).build();

    connection.sendRealtime(video).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(session).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().video()).hasValue(video);
    assertThat(realtimeCaptor.getValue().media()).isEmpty();
  }

  @Test
  public void sendRealtime_withGemini25Live_keepsMediaField() {
    AsyncSession session = mock(AsyncSession.class);
    when(session.sendRealtimeInput(any())).thenReturn(CompletableFuture.completedFuture(null));
    GeminiLlmConnection connection = connectionForSession(session, "gemini-2.5-flash-live-preview");
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=24000").data(new byte[] {1}).build();

    connection.sendRealtime(audio).blockingAwait();

    ArgumentCaptor<LiveSendRealtimeInputParameters> realtimeCaptor =
        ArgumentCaptor.forClass(LiveSendRealtimeInputParameters.class);
    verify(session).sendRealtimeInput(realtimeCaptor.capture());
    assertThat(realtimeCaptor.getValue().media()).hasValue(audio);
    assertThat(realtimeCaptor.getValue().audio()).isEmpty();
  }

  private static GeminiLlmConnection connectionForSession(AsyncSession session, String modelName) {
    return new GeminiLlmConnection(
        CompletableFuture.completedFuture(session), modelName, LiveConnectConfig.builder().build());
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
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(functionCall);
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
  public void convertToServerResponse_withSessionResumptionUpdate_mapsUpdate() {
    LiveServerSessionResumptionUpdate update =
        LiveServerSessionResumptionUpdate.builder()
            .newHandle("resume-handle")
            .resumable(true)
            .lastConsumedClientMessageIndex(17L)
            .build();
    LiveServerMessage message = LiveServerMessage.builder().sessionResumptionUpdate(update).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    assertThat(testObserver.values().get(0).liveSessionResumptionUpdate()).hasValue(update);
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
}
