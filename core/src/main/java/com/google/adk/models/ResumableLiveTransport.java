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

import com.google.adk.agents.RunConfig.LiveRecoveryEvent;
import com.google.adk.agents.RunConfig.LiveRecoveryEvent.Phase;
import com.google.genai.JsonSerializable;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSessionResumptionUpdate;
import com.google.genai.types.SessionResumptionConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Connection-local transparent resumption. The existing flow remains the input queue/sender. */
final class ResumableLiveTransport implements GeminiLiveTransport {
  static final String WINDOW_EXHAUSTED = "LIVE_RECOVERY_REPLAY_WINDOW_EXHAUSTED";
  static final String CHECKPOINT_INVALID = "LIVE_RECOVERY_CHECKPOINT_INVALID";
  static final String DELIVERY_UNKNOWN = "LIVE_RECOVERY_DELIVERY_UNKNOWN";

  private final Function<LiveConnectConfig, CompletableFuture<GeminiLiveTransport>> factory;
  private final LiveConnectConfig config;
  private final int maxMessages;
  private final long maxBytes;
  private final boolean transparent;
  private final @Nullable Consumer<LiveRecoveryEvent> recoveryObserver;
  private final CompletableFuture<Void> receiveResult = new CompletableFuture<>();
  private final Object lock = new Object();
  private Session current;
  private CompletableFuture<Session> ready;
  private Consumer<LiveServerMessage> onMessage;
  private Runnable onStreamEnd;
  private boolean stopped;
  private ArrayDeque<Operation> activeReplay = new ArrayDeque<>();
  private long upstreamConnectionGeneration;
  private long acceptedCheckpointCount;
  private long replayedMessageCount;
  private long replayedByteCount;

  ResumableLiveTransport(
      Function<LiveConnectConfig, CompletableFuture<GeminiLiveTransport>> factory,
      LiveConnectConfig config,
      int maxMessages,
      long maxBytes,
      @Nullable Consumer<LiveRecoveryEvent> recoveryObserver) {
    if (maxMessages <= 0 || maxBytes <= 0) {
      throw new IllegalArgumentException("Live resumption replay limits must be positive.");
    }
    this.factory = factory;
    this.config = config;
    this.maxMessages = maxMessages;
    this.maxBytes = maxBytes;
    this.recoveryObserver = recoveryObserver;
    this.transparent =
        config.sessionResumption().flatMap(SessionResumptionConfig::transparent).orElse(false);
    current = new Session();
    ready = current.connected;
    open(current, config);
  }

  private void open(Session session, LiveConnectConfig setup) {
    CompletableFuture<GeminiLiveTransport> opening;
    try {
      opening = factory.apply(setup);
    } catch (RuntimeException error) {
      fail(DELIVERY_UNKNOWN);
      session.connected.completeExceptionally(failure(DELIVERY_UNKNOWN));
      return;
    }
    opening.whenComplete(
        (transport, error) -> {
          synchronized (lock) {
            if (stopped || session != current) {
              if (transport != null) {
                closeSafely(transport);
              }
              session.connected.completeExceptionally(failure(DELIVERY_UNKNOWN));
              return;
            }
            if (error != null || transport == null) {
              fail(DELIVERY_UNKNOWN);
              session.connected.completeExceptionally(failure(DELIVERY_UNKNOWN));
              return;
            }
            session.transport = transport;
            session.generation = ++upstreamConnectionGeneration;
            installReceiver(session);
            session.connected.complete(session);
          }
        });
  }

  @Override
  public CompletableFuture<Void> receive(
      Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
    synchronized (lock) {
      this.onMessage = onMessage;
      this.onStreamEnd = onStreamEnd;
      installReceiver(current);
      return receiveResult;
    }
  }

  private void installReceiver(Session session) {
    if (onMessage == null || session.transport == null || session.receiving || stopped) {
      return;
    }
    session.receiving = true;
    try {
      session
          .transport
          .receive(message -> receiveMessage(session, message), () -> streamEnded(session))
          .whenComplete(
              (unused, error) -> {
                if (error != null) {
                  transportFailed(session);
                }
              });
    } catch (RuntimeException error) {
      transportFailed(session);
    }
  }

  private void receiveMessage(Session session, LiveServerMessage message) {
    synchronized (lock) {
      if (stopped || session != current || session.handoff) {
        return;
      }
      message.sessionResumptionUpdate().ifPresent(update -> checkpoint(session, update));
      if (message.goAway().isPresent()) {
        rotate(
            session,
            () -> {
              observe(Phase.GO_AWAY_RECEIVED, session.generation);
              onMessage.accept(message);
            });
      } else {
        onMessage.accept(message);
      }
    }
  }

  private void checkpoint(Session session, LiveServerSessionResumptionUpdate update) {
    Long consumed = update.lastConsumedClientMessageIndex().orElse(null);
    String handle = update.newHandle().orElse("");
    if (!transparent
        || !update.resumable().orElse(false)
        || handle.isBlank()
        || consumed == null
        || consumed < session.acknowledged
        || consumed > session.highest) {
      session.checkpointValid = false;
      return;
    }
    Checkpoint checkpoint = new Checkpoint(handle, consumed);
    if (!checkpoint.equals(session.checkpoint)) {
      acceptedCheckpointCount++;
    }
    session.acknowledged = consumed;
    session.checkpoint = checkpoint;
    session.checkpointValid = true;
    while (!session.window.isEmpty() && session.window.peekFirst().index <= consumed) {
      session.retainedBytes -= session.window.removeFirst().operation.bytes;
    }
  }

  @Override
  public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters parameters) {
    return send(new Operation(parameters, transport -> transport.sendClientContent(parameters)));
  }

  @Override
  public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters parameters) {
    return send(new Operation(parameters, transport -> transport.sendRealtimeInput(parameters)));
  }

  @Override
  public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters parameters) {
    return send(new Operation(parameters, transport -> transport.sendToolResponse(parameters)));
  }

  private CompletableFuture<Void> send(Operation operation) {
    synchronized (lock) {
      if (stopped) {
        return CompletableFuture.failedFuture(failure(DELIVERY_UNKNOWN));
      }
      return ready.thenCompose(
          session -> {
            synchronized (lock) {
              if (session != current || session.handoff) {
                return send(operation);
              }
              return sendNow(session, operation);
            }
          });
    }
  }

  private CompletableFuture<Void> sendNow(Session session, Operation operation) {
    synchronized (lock) {
      return sendLocked(session, operation);
    }
  }

  private CompletableFuture<Void> sendLocked(Session session, Operation operation) {
    if (stopped) {
      return CompletableFuture.failedFuture(failure(DELIVERY_UNKNOWN));
    }
    if (transparent) {
      long bytes;
      try {
        bytes = Math.addExact(session.retainedBytes, operation.bytes);
      } catch (ArithmeticException error) {
        fail(WINDOW_EXHAUSTED);
        return CompletableFuture.failedFuture(failure(WINDOW_EXHAUSTED));
      }
      if (session.window.size() >= maxMessages || bytes > maxBytes) {
        fail(WINDOW_EXHAUSTED);
        return CompletableFuture.failedFuture(failure(WINDOW_EXHAUSTED));
      }
      session.retainedBytes = bytes;
      session.window.addLast(new IndexedOperation(++session.highest, operation));
    }
    CompletableFuture<Void> sent;
    try {
      sent = operation.send.apply(session.transport);
    } catch (RuntimeException error) {
      sent = CompletableFuture.failedFuture(failure(DELIVERY_UNKNOWN));
    }
    session.inFlight = sent;
    return sent.handle(
            (unused, error) -> {
              if (error != null) {
                synchronized (lock) {
                  // A trustworthy index makes a failed operation part of the known unacknowledged
                  // suffix.
                  if (!stopped && !session.replaying) {
                    if (!session.handoff && session == current && session.checkpointValid) {
                      rotate(session);
                    }
                    if (session.handoff && !stopped) {
                      return ready.thenApply(ignored -> (Void) null);
                    }
                  }
                  fail(DELIVERY_UNKNOWN);
                }
                return CompletableFuture.<Void>failedFuture(failure(DELIVERY_UNKNOWN));
              }
              return CompletableFuture.<Void>completedFuture(null);
            })
        .thenCompose(Function.identity());
  }

  private void streamEnded(Session session) {
    synchronized (lock) {
      if (stopped || session != current || session.handoff) {
        return;
      }
      if (transparent) {
        rotate(session);
      } else {
        stopped = true;
        onStreamEnd.run();
        receiveResult.complete(null);
      }
    }
  }

  private void transportFailed(Session session) {
    synchronized (lock) {
      if (!stopped && session == current && !session.handoff) {
        if (!session.checkpointValid) {
          fail(DELIVERY_UNKNOWN);
        } else {
          rotate(session);
        }
      }
    }
  }

  private void rotate(Session old) {
    rotate(old, () -> {});
  }

  private void rotate(Session old, Runnable notifyGoAway) {
    if (stopped || old != current || old.handoff) {
      return;
    }
    if (old.replaying) {
      fail(DELIVERY_UNKNOWN);
      return;
    }
    old.handoff = true;
    CompletableFuture<Session> handoff = new CompletableFuture<>();
    ready = handoff;
    // Install the sender barrier before publishing a notification whose consumer can enqueue input.
    notifyGoAway.run();
    if (stopped) {
      return;
    }
    if (!old.checkpointValid) {
      fail(CHECKPOINT_INVALID);
      return;
    }
    ArrayDeque<Operation> replay = new ArrayDeque<>();
    for (IndexedOperation entry : old.window) {
      replay.add(entry.operation);
    }
    activeReplay = replay;
    long replayMessages = replay.size();
    long replayBytes = old.retainedBytes;
    LiveConnectConfig resumedConfig =
        config.toBuilder()
            .sessionResumption(
                config.sessionResumption().orElseThrow().toBuilder()
                    .handle(old.checkpoint.handle())
                    .build())
            .build();
    old.inFlight
        .handle((unused, error) -> null)
        .thenCompose(unused -> closeSession(old))
        .whenComplete(
            (unused, error) -> {
              synchronized (lock) {
                if (stopped) {
                  handoff.completeExceptionally(failure(DELIVERY_UNKNOWN));
                  return;
                }
                if (error != null) {
                  fail(DELIVERY_UNKNOWN);
                  handoff.completeExceptionally(failure(DELIVERY_UNKNOWN));
                  return;
                }
                old.window.clear();
                old.retainedBytes = 0;
                Session replacement = new Session();
                replacement.replaying = true;
                current = replacement;
                replacement
                    .connected
                    .thenCompose(this::replayNext)
                    .whenComplete(
                        (ignored, replayError) -> {
                          synchronized (lock) {
                            if (stopped || replayError != null) {
                              fail(DELIVERY_UNKNOWN);
                              handoff.completeExceptionally(failure(DELIVERY_UNKNOWN));
                            } else {
                              replacement.replaying = false;
                              replay.clear();
                              replayedMessageCount += replayMessages;
                              replayedByteCount += replayBytes;
                              observe(Phase.ROTATION_COMPLETED, replacement.generation);
                              handoff.complete(replacement);
                            }
                          }
                        });
                open(replacement, resumedConfig);
              }
            });
  }

  private void observe(Phase phase, long generation) {
    if (recoveryObserver == null) {
      return;
    }
    try {
      recoveryObserver.accept(
          new LiveRecoveryEvent(
              phase, generation, acceptedCheckpointCount, replayedMessageCount, replayedByteCount));
    } catch (Throwable ignored) {
      // A diagnostic sink must never become part of recovery correctness, including its failures.
    }
  }

  private CompletableFuture<Void> replayNext(Session session) {
    synchronized (lock) {
      if (stopped || session != current) {
        return CompletableFuture.failedFuture(failure(DELIVERY_UNKNOWN));
      }
      Operation operation;
      while ((operation = activeReplay.pollFirst()) != null) {
        CompletableFuture<Void> sent = sendNow(session, operation);
        if (!sent.isDone() || sent.isCompletedExceptionally()) {
          return sent.thenCompose(unused -> replayNext(session));
        }
      }
      return CompletableFuture.completedFuture(null);
    }
  }

  private void fail(String code) {
    close();
    receiveResult.completeExceptionally(failure(code));
  }

  private static IllegalStateException failure(String code) {
    return new IllegalStateException(code);
  }

  private CompletableFuture<Void> closeSession(Session session) {
    synchronized (lock) {
      if (session.closing == null) {
        session.closing = closeSafely(session.transport);
      }
      return session.closing;
    }
  }

  private static CompletableFuture<Void> closeSafely(GeminiLiveTransport transport) {
    try {
      return transport
          .close()
          .handle(
              (unused, error) -> {
                if (error != null) {
                  throw failure(DELIVERY_UNKNOWN);
                }
                return null;
              });
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(failure(DELIVERY_UNKNOWN));
    }
  }

  @Override
  public CompletableFuture<Void> close() {
    synchronized (lock) {
      if (stopped) {
        return CompletableFuture.completedFuture(null);
      }
      stopped = true;
      current.window.clear();
      current.retainedBytes = 0;
      activeReplay.clear();
      if (!ready.isDone()) {
        ready.completeExceptionally(failure(DELIVERY_UNKNOWN));
      }
      return current.transport == null
          ? CompletableFuture.completedFuture(null)
          : closeSession(current);
    }
  }

  private static final class Session {
    final CompletableFuture<Session> connected = new CompletableFuture<>();
    final ArrayDeque<IndexedOperation> window = new ArrayDeque<>();
    GeminiLiveTransport transport;
    CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);
    CompletableFuture<Void> closing;
    long highest;
    long acknowledged;
    long retainedBytes;
    Checkpoint checkpoint;
    boolean checkpointValid;
    long generation;
    boolean handoff;
    boolean receiving;
    boolean replaying;
  }

  private record Checkpoint(String handle, long consumedIndex) {}

  private record IndexedOperation(long index, Operation operation) {}

  private static final class Operation {
    final long bytes;
    final Function<GeminiLiveTransport, CompletableFuture<Void>> send;

    Operation(
        JsonSerializable parameters, Function<GeminiLiveTransport, CompletableFuture<Void>> send) {
      this.bytes = parameters.toJson().getBytes(StandardCharsets.UTF_8).length;
      this.send = send;
    }
  }
}
