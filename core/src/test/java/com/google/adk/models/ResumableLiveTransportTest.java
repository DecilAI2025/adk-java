/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.RunConfig.LiveRecoveryEvent;
import com.google.adk.agents.RunConfig.LiveRecoveryEvent.Phase;
import com.google.genai.Client;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerGoAway;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSessionResumptionUpdate;
import com.google.genai.types.Part;
import com.google.genai.types.SessionResumptionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

/** Deterministic public-transport signals; no SDK-hidden socket callbacks are simulated. */
public final class ResumableLiveTransportTest {
  private static final LiveSendRealtimeInputParameters TEXT =
      LiveSendRealtimeInputParameters.builder().text("synthetic input").build();

  @Test
  public void geminiConnectOnlySelectsReplayWhenTransparentModeIsEnabled() throws Exception {
    for (boolean transparent : List.of(false, true)) {
      Fake first = new Fake();
      Fake second = new Fake();
      List<LiveRecoveryEvent> observations = new ArrayList<>();
      List<LiveConnectConfig> configs = new ArrayList<>();
      Client client = mock(Client.class);
      when(client.vertexAI()).thenReturn(true);
      Gemini model =
          new Gemini("gemini-2.5-flash-live", client) {
            @Override
            protected CompletableFuture<GeminiLiveTransport> connectLiveTransport(
                String modelName, LiveConnectConfig config) {
              configs.add(config);
              return CompletableFuture.completedFuture(configs.size() == 1 ? first : second);
            }
          };
      BaseLlmConnection connection =
          model.connect(
              LlmRequest.builder()
                  .liveRecoveryObserver(observations::add)
                  .liveConnectConfig(
                      LiveConnectConfig.builder()
                          .sessionResumption(
                              SessionResumptionConfig.builder().transparent(transparent).build())
                          .build())
                  .build());
      var responses = connection.receive().test();
      first.receiving.get(5, TimeUnit.SECONDS);
      first.emit(checkpoint("valid", 0));
      first.emit(goAway());

      assertThat(configs).hasSize(transparent ? 2 : 1);
      responses.assertValueCount(2).assertNoErrors().assertNotComplete();
      assertThat(responses.values().get(1).liveGoAway()).isPresent();
      assertThat(observations).hasSize(transparent ? 2 : 0);
      if (transparent) {
        assertThat(observations.get(0))
            .isEqualTo(new LiveRecoveryEvent(Phase.GO_AWAY_RECEIVED, 1, 1, 0, 0));
        assertThat(observations.get(1))
            .isEqualTo(new LiveRecoveryEvent(Phase.ROTATION_COMPLETED, 2, 1, 0, 0));
      }
      connection.close();
    }
  }

  @Test
  public void observerCountersAreCumulativeAndExcludeDuplicateOrRejectedCheckpoints() {
    List<LiveRecoveryEvent> observed = new ArrayList<>();
    Harness h = new Harness(5, 100000, observed::add);
    h.transport.sendRealtimeInput(TEXT).join();
    h.transport.sendRealtimeInput(TEXT).join();
    h.first.emit(checkpoint("first", 1));
    h.first.emit(checkpoint("first", 1));
    h.first.emit(checkpoint("rejected-out-of-range", 3));
    h.first.emit(checkpoint("first", 1));
    h.first.emit(checkpoint("new-handle-same-index", 1));

    h.first.emit(goAway());
    h.first.emit(goAway());
    assertThat(observed).containsExactly(new LiveRecoveryEvent(Phase.GO_AWAY_RECEIVED, 1, 2, 0, 0));
    CompletableFuture<Void> queued = h.transport.sendRealtimeInput(TEXT);
    h.next.complete(h.second);
    queued.join();
    long bytes = TEXT.toJson().getBytes(UTF_8).length;
    assertThat(observed.get(1))
        .isEqualTo(new LiveRecoveryEvent(Phase.ROTATION_COMPLETED, 2, 2, 1, bytes));

    h.second.emit(checkpoint("second", 1));
    h.second.emit(checkpoint("second", 1));
    h.second.emit(goAway());
    assertThat(observed.get(2))
        .isEqualTo(new LiveRecoveryEvent(Phase.GO_AWAY_RECEIVED, 2, 3, 1, bytes));
    h.afterNext.complete(h.third);

    assertThat(observed.get(3))
        .isEqualTo(new LiveRecoveryEvent(Phase.ROTATION_COMPLETED, 3, 3, 2, 2 * bytes));
    assertThat(observed).hasSize(4);
    assertThat(h.third.sent).containsExactly(TEXT);
  }

  @Test
  public void observerRunsBehindGoAwayBarrierAndCannotAffectRecoveryWhenItFails() {
    AtomicReference<Harness> harness = new AtomicReference<>();
    AtomicReference<CompletableFuture<Void>> queued = new AtomicReference<>();
    List<Phase> observed = new ArrayList<>();
    Harness h =
        new Harness(
            5,
            100000,
            event -> {
              observed.add(event.phase());
              if (event.phase() == Phase.GO_AWAY_RECEIVED) {
                queued.set(harness.get().transport.sendRealtimeInput(TEXT));
                assertThat(queued.get().isDone()).isFalse();
                throw new IllegalStateException("synthetic sink failure");
              }
              throw new AssertionError("synthetic sink failure");
            });
    harness.set(h);
    h.first.emit(checkpoint("valid", 0));
    h.first.emit(goAway());
    assertThat(h.first.sent).isEmpty();
    assertThat(queued.get().isDone()).isFalse();
    h.next.complete(h.second);
    queued.get().join();

    assertThat(h.second.sent).containsExactly(TEXT);
    assertThat(observed)
        .containsExactly(Phase.GO_AWAY_RECEIVED, Phase.ROTATION_COMPLETED)
        .inOrder();
    assertThat(h.receiveResult.isDone()).isFalse();
  }

  @Test
  public void observerDoesNotClaimCompletionForFailedOrCancelledReplacement() {
    for (int outcome = 0; outcome < 3; outcome++) {
      List<LiveRecoveryEvent> observed = new ArrayList<>();
      Harness h = new Harness(5, 100000, observed::add);
      h.transport.sendRealtimeInput(TEXT).join();
      h.first.emit(checkpoint("valid", 0));
      h.first.emit(goAway());
      if (outcome == 0) {
        h.transport.close();
        h.next.complete(h.second);
      } else if (outcome == 1) {
        h.next.completeExceptionally(new IllegalStateException("synthetic open failure"));
      } else {
        h.second.sendResult =
            CompletableFuture.failedFuture(new IllegalStateException("synthetic replay failure"));
        h.next.complete(h.second);
      }

      assertThat(observed)
          .containsExactly(new LiveRecoveryEvent(Phase.GO_AWAY_RECEIVED, 1, 1, 0, 0));
    }
  }

  @Test
  public void observerEventHasOnlyFrozenPhaseAndScalarCounters() {
    var fields = LiveRecoveryEvent.class.getRecordComponents();
    assertThat(
            java.util.Arrays.stream(fields)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList())
        .containsExactly(
            "phase",
            "upstreamConnectionGeneration",
            "acceptedCheckpointCount",
            "replayedMessageCount",
            "replayedByteCount")
        .inOrder();
    assertThat(
            java.util.Arrays.stream(fields)
                .map(java.lang.reflect.RecordComponent::getType)
                .toList())
        .containsExactly(Phase.class, long.class, long.class, long.class, long.class)
        .inOrder();
  }

  @Test
  public void goAway_replaysEveryExactOperationThenQueuedInputOnSameReceiver() {
    List<LiveRecoveryEvent> observed = new ArrayList<>();
    Harness h = new Harness(10, 100000, observed::add);
    h.first.emit(checkpoint("old", 0));
    h.first.emit(checkpoint("latest", 0));
    LiveSendClientContentParameters content =
        LiveSendClientContentParameters.builder()
            .turns(List.of(Content.fromParts(Part.fromText("synthetic instruction"))))
            .turnComplete(false)
            .build();
    LiveSendRealtimeInputParameters audio =
        LiveSendRealtimeInputParameters.builder()
            .audio(Blob.builder().mimeType("audio/pcm").data(new byte[] {1, 2}).build())
            .build();
    LiveSendRealtimeInputParameters video =
        LiveSendRealtimeInputParameters.builder()
            .video(Blob.builder().mimeType("image/jpeg").data(new byte[] {3}).build())
            .build();
    LiveSendToolResponseParameters tool =
        LiveSendToolResponseParameters.builder()
            .functionResponses(
                List.of(
                    FunctionResponse.builder().name("synthetic_tool").response(Map.of()).build()))
            .build();
    h.transport.sendClientContent(content).join();
    h.transport.sendRealtimeInput(TEXT).join();
    h.transport.sendRealtimeInput(audio).join();
    h.transport.sendRealtimeInput(video).join();
    h.transport.sendToolResponse(tool).join();

    h.first.emit(goAway());
    h.first.emit(goAway());
    CompletableFuture<Void> queued = h.transport.sendRealtimeInput(TEXT);
    assertThat(queued.isDone()).isFalse();
    assertThat(h.configs).hasSize(2);
    assertThat(h.first.closes).isEqualTo(1);
    assertThat(h.first.sent).hasSize(5);
    h.next.complete(h.second);
    queued.join();

    assertThat(h.configs.get(1).sessionResumption().orElseThrow().handle()).hasValue("latest");
    assertThat(h.configs.get(1).sessionResumption().orElseThrow().transparent()).hasValue(true);
    assertThat(h.second.sent).containsExactly(content, TEXT, audio, video, tool, TEXT).inOrder();
    assertThat(h.messages).hasSize(3); // Two checkpoint events and the first GoAway only.
    assertThat(h.receiveResult.isDone()).isFalse();
    long replayBytes =
        h.first.sent.stream()
            .mapToLong(parameters -> parameters.toJson().getBytes(UTF_8).length)
            .sum();
    assertThat(observed)
        .containsExactly(
            new LiveRecoveryEvent(Phase.GO_AWAY_RECEIVED, 1, 2, 0, 0),
            new LiveRecoveryEvent(Phase.ROTATION_COMPLETED, 2, 2, 5, replayBytes))
        .inOrder();
  }

  @Test
  public void goAwayBarrierPrecedesNotificationConsumersThatEnqueueNewInput() {
    Harness h = new Harness(5, 100000);
    h.first.emit(checkpoint("valid", 0));
    AtomicReference<CompletableFuture<Void>> queued = new AtomicReference<>();
    h.transport.receive(
        message -> {
          if (message.goAway().isPresent()) {
            queued.set(h.transport.sendRealtimeInput(TEXT));
            assertThat(h.first.sent).isEmpty();
          }
        },
        () -> {});

    h.first.emit(goAway());
    assertThat(queued.get().isDone()).isFalse();
    h.next.complete(h.second);

    queued.get().join();
    assertThat(h.first.sent).isEmpty();
    assertThat(h.second.sent).containsExactly(TEXT);
  }

  @Test
  public void checkpoint_prunesAcknowledgedSuffixAndResetsIndicesAfterReplacement() {
    Harness h = new Harness(5, 100000);
    for (int i = 0; i < 5; i++) {
      h.transport.sendRealtimeInput(TEXT).join();
    }
    h.first.emit(checkpoint("four-consumed", 4));
    h.first.emit(goAway());
    h.next.complete(h.second);
    assertThat(h.second.sent).containsExactly(TEXT);

    h.second.emit(checkpoint("new-connection-one", 1));
    for (int i = 0; i < 5; i++) {
      h.transport.sendRealtimeInput(TEXT).join();
    }
    // A valid index of 1 on this connection releases the replayed payload even though old ack was
    // 4.
    assertThat(h.second.sent).hasSize(6);
    assertThat(h.receiveResult.isCompletedExceptionally()).isFalse();
  }

  @Test
  public void missingOrIncompleteRegressingAndOutOfRangeCheckpointFailWithoutFreshFallback() {
    List<LiveServerSessionResumptionUpdate> invalid =
        List.of(
            LiveServerSessionResumptionUpdate.builder().resumable(false).build(),
            LiveServerSessionResumptionUpdate.builder().resumable(true).newHandle("h").build(),
            LiveServerSessionResumptionUpdate.builder()
                .resumable(true)
                .lastConsumedClientMessageIndex(1L)
                .build(),
            LiveServerSessionResumptionUpdate.builder()
                .resumable(true)
                .newHandle("h")
                .lastConsumedClientMessageIndex(-1L)
                .build(),
            LiveServerSessionResumptionUpdate.builder()
                .resumable(true)
                .newHandle("h")
                .lastConsumedClientMessageIndex(2L)
                .build(),
            LiveServerSessionResumptionUpdate.builder()
                .resumable(true)
                .newHandle("h")
                .lastConsumedClientMessageIndex(0L)
                .build());
    for (LiveServerSessionResumptionUpdate update : invalid) {
      Harness h = new Harness(5, 100000);
      h.transport.sendRealtimeInput(TEXT).join();
      h.first.emit(checkpoint("previous-valid", 1));
      h.first.emit(LiveServerMessage.builder().sessionResumptionUpdate(update).build());
      h.first.emit(goAway());
      assertSafeFailure(h.receiveResult, ResumableLiveTransport.CHECKPOINT_INVALID);
      assertThat(h.configs).hasSize(1);
    }
    Harness missing = new Harness(5, 100000);
    missing.first.emit(goAway());
    assertSafeFailure(missing.receiveResult, ResumableLiveTransport.CHECKPOINT_INVALID);
  }

  @Test
  public void countExhaustionFailsBeforeSendingAndAcknowledgementReleasesCapacity() {
    Harness full = new Harness(1, 100000);
    full.transport.sendRealtimeInput(TEXT).join();
    assertSafeFailure(
        full.transport.sendRealtimeInput(TEXT), ResumableLiveTransport.WINDOW_EXHAUSTED);
    assertThat(full.first.sent).hasSize(1);

    Harness pruned = new Harness(1, 100000);
    pruned.transport.sendRealtimeInput(TEXT).join();
    pruned.first.emit(checkpoint("one", 1));
    pruned.transport.sendRealtimeInput(TEXT).join();
    assertThat(pruned.first.sent).hasSize(2);
  }

  @Test
  public void bytesUseExactTypedParameterUtf8IncludingMultibyteText() {
    LiveSendRealtimeInputParameters multibyte =
        LiveSendRealtimeInputParameters.builder().text("日本語 🦀").build();
    int bytes = multibyte.toJson().getBytes(UTF_8).length;
    Harness tooSmall = new Harness(5, bytes - 1);
    assertSafeFailure(
        tooSmall.transport.sendRealtimeInput(multibyte), ResumableLiveTransport.WINDOW_EXHAUSTED);
    assertThat(tooSmall.first.sent).isEmpty();

    Harness exact = new Harness(5, bytes);
    exact.transport.sendRealtimeInput(multibyte).join();
    exact.first.emit(checkpoint("prune-bytes", 1));
    exact.transport.sendRealtimeInput(multibyte).join();
    assertThat(exact.first.sent).hasSize(2);
  }

  @Test
  public void closeDuringHandoffPreventsReplacementAndCancelsQueuedInput() {
    Harness h = new Harness(5, 100000);
    h.first.closeResult = new CompletableFuture<>();
    h.first.emit(checkpoint("valid", 0));
    h.first.emit(goAway());
    CompletableFuture<Void> queued = h.transport.sendRealtimeInput(TEXT);

    h.transport.close();
    h.first.closeResult.complete(null);

    assertThat(h.configs).hasSize(1);
    assertThat(h.first.sent).isEmpty();
    assertThat(h.first.closes).isEqualTo(1);
    assertSafeFailure(queued, ResumableLiveTransport.DELIVERY_UNKNOWN);
  }

  @Test
  public void closeDuringConnectClosesLateReplacementWithoutReplay() {
    Harness h = new Harness(5, 100000);
    h.transport.sendRealtimeInput(TEXT).join();
    h.first.emit(checkpoint("valid", 0));
    h.first.emit(goAway());
    h.transport.close();
    h.next.complete(h.second);

    assertThat(h.second.closes).isEqualTo(1);
    assertThat(h.second.sent).isEmpty();
  }

  @Test
  public void sendFailureWithoutCheckpointSurfacesOnlySafeReason() {
    Harness h = new Harness(5, 100000);
    h.first.sendResult =
        CompletableFuture.failedFuture(new IllegalStateException("unsafe-provider"));

    assertSafeFailure(h.transport.sendRealtimeInput(TEXT), ResumableLiveTransport.DELIVERY_UNKNOWN);
    assertSafeFailure(h.receiveResult, ResumableLiveTransport.DELIVERY_UNKNOWN);
    assertThat(h.configs).hasSize(1);
  }

  @Test
  public void sendFailureWithTrustedCheckpointReplaysOnceAndCompletesOriginalSend() {
    Harness h = new Harness(5, 100000);
    h.first.emit(checkpoint("valid", 0));
    h.first.sendResult =
        CompletableFuture.failedFuture(new IllegalStateException("unsafe-provider"));

    CompletableFuture<Void> sent = h.transport.sendRealtimeInput(TEXT);
    h.next.complete(h.second);

    sent.join();
    assertThat(h.configs).hasSize(2);
    assertThat(h.second.sent).containsExactly(TEXT);
    assertThat(h.receiveResult.isDone()).isFalse();
  }

  @Test
  public void failureDuringReplayDoesNotOpenThirdConnection() {
    Harness h = new Harness(5, 100000);
    h.first.emit(checkpoint("valid", 0));
    h.transport.sendRealtimeInput(TEXT).join();
    h.second.sendResult =
        CompletableFuture.failedFuture(new IllegalStateException("unsafe-provider"));
    h.first.emit(goAway());
    h.next.complete(h.second);

    assertSafeFailure(h.receiveResult, ResumableLiveTransport.DELIVERY_UNKNOWN);
    assertThat(h.configs).hasSize(2);
  }

  @Test
  public void publiclySurfacedReceiveFailureUsesCheckpointButCannotClaimHiddenSdkClosure() {
    Harness h = new Harness(5, 100000);
    h.first.emit(checkpoint("valid", 0));
    h.first.receiveResult.completeExceptionally(new IllegalStateException("unsafe-provider"));
    h.next.complete(h.second);

    assertThat(h.configs).hasSize(2);
    assertThat(h.receiveResult.isDone()).isFalse();
  }

  @Test
  public void handoffWaitsForOldSendToSettleAndOldCloseBeforeOpeningReplacement() {
    Harness h = new Harness(5, 100000);
    h.first.emit(checkpoint("valid", 0));
    h.first.sendResult = new CompletableFuture<>();
    h.first.closeResult = new CompletableFuture<>();
    CompletableFuture<Void> sent = h.transport.sendRealtimeInput(TEXT);

    h.first.emit(goAway());
    assertThat(h.first.closes).isEqualTo(0);
    assertThat(h.configs).hasSize(1);
    h.first.sendResult.complete(null);
    sent.join();
    assertThat(h.first.closes).isEqualTo(1);
    assertThat(h.configs).hasSize(1);
    h.first.closeResult.complete(null);
    assertThat(h.configs).hasSize(2);
  }

  @Test
  public void maximumWindowCanReplayWithoutRecursiveStackGrowth() {
    Harness h = new Harness(4096, 8388608);
    h.first.emit(checkpoint("valid", 0));
    for (int i = 0; i < 4096; i++) {
      h.transport.sendRealtimeInput(TEXT).join();
    }
    h.first.emit(goAway());
    h.next.complete(h.second);

    assertThat(h.second.sent).hasSize(4096);
    assertThat(h.receiveResult.isDone()).isFalse();
  }

  private static LiveServerMessage checkpoint(String handle, long consumed) {
    return LiveServerMessage.builder()
        .sessionResumptionUpdate(
            LiveServerSessionResumptionUpdate.builder()
                .resumable(true)
                .newHandle(handle)
                .lastConsumedClientMessageIndex(consumed)
                .build())
        .build();
  }

  private static LiveServerMessage goAway() {
    return LiveServerMessage.builder().goAway(LiveServerGoAway.builder().build()).build();
  }

  private static void assertSafeFailure(CompletableFuture<?> future, String code) {
    CompletionException failure = assertThrows(CompletionException.class, future::join);
    assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
    assertThat(failure.getCause()).hasMessageThat().isEqualTo(code);
    assertThat(failure.getCause().getCause()).isNull();
  }

  private static final class Harness {
    final Fake first = new Fake();
    final Fake second = new Fake();
    final Fake third = new Fake();
    final CompletableFuture<GeminiLiveTransport> next = new CompletableFuture<>();
    final CompletableFuture<GeminiLiveTransport> afterNext = new CompletableFuture<>();
    final List<LiveConnectConfig> configs = new ArrayList<>();
    final List<LiveServerMessage> messages = new ArrayList<>();
    final ResumableLiveTransport transport;
    final CompletableFuture<Void> receiveResult;

    Harness(int maxMessages, long maxBytes) {
      this(maxMessages, maxBytes, null);
    }

    Harness(int maxMessages, long maxBytes, Consumer<LiveRecoveryEvent> observer) {
      transport =
          new ResumableLiveTransport(
              config -> {
                configs.add(config);
                return switch (configs.size()) {
                  case 1 -> CompletableFuture.completedFuture(first);
                  case 2 -> next;
                  case 3 -> afterNext;
                  default -> throw new AssertionError("unexpected provider connection");
                };
              },
              LiveConnectConfig.builder()
                  .sessionResumption(SessionResumptionConfig.builder().transparent(true).build())
                  .build(),
              maxMessages,
              maxBytes,
              observer);
      receiveResult = transport.receive(messages::add, () -> {});
    }
  }

  private static final class Fake implements GeminiLiveTransport {
    final List<JsonSerializable> sent = new ArrayList<>();
    final CompletableFuture<Void> receiveResult = new CompletableFuture<>();
    final CompletableFuture<Void> receiving = new CompletableFuture<>();
    CompletableFuture<Void> sendResult = CompletableFuture.completedFuture(null);
    CompletableFuture<Void> closeResult = CompletableFuture.completedFuture(null);
    Consumer<LiveServerMessage> receiver;
    int closes;

    void emit(LiveServerMessage message) {
      receiver.accept(message);
    }

    @Override
    public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters parameters) {
      sent.add(parameters);
      return sendResult;
    }

    @Override
    public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters parameters) {
      sent.add(parameters);
      return sendResult;
    }

    @Override
    public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters parameters) {
      sent.add(parameters);
      return sendResult;
    }

    @Override
    public CompletableFuture<Void> receive(Consumer<LiveServerMessage> onMessage, Runnable onEnd) {
      receiver = onMessage;
      receiving.complete(null);
      return receiveResult;
    }

    @Override
    public CompletableFuture<Void> close() {
      closes++;
      return closeResult;
    }
  }
}
