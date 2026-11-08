package org.openbot.app.robot.voice.relay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import timber.log.Timber;

/**
 * A tiny MQTT 3.1.1 client over secure WebSocket (OkHttp), just enough for the Internet Mic:
 * CONNECT, SUBSCRIBE (QoS 0), PUBLISH (QoS 0), keep-alive pings, DISCONNECT. Works over any
 * internet connection — WiFi or 4G/5G — because both phones only dial OUT to the broker.
 *
 * <p>Callbacks run on OkHttp's thread; {@link #publish} may be called from any thread.
 */
final class MqttWs {

  interface Callback {
    /** Connected and subscribed: publishing works now. */
    void onReady(MqttWs mqtt);

    void onMessage(MqttWs mqtt, String topic, byte[] payload);

    /** The connection ended (refused, network gone, broker closed it). */
    void onLost(MqttWs mqtt, String why);
  }

  private static final int KEEP_ALIVE_S = 60;
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.SECONDS)
          .build();
  private static final ScheduledExecutorService TIMER =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "mqtt-ws-ping");
            t.setDaemon(true);
            return t;
          });

  final String url;
  private final String clientId;
  private final List<String> topics;
  private final Callback callback;
  private final ByteArrayOutputStream inbox = new ByteArrayOutputStream();

  private volatile WebSocket ws;
  private volatile boolean ready;
  private volatile boolean ended;
  private ScheduledFuture<?> pinger;

  MqttWs(String url, String clientId, List<String> topics, Callback callback) {
    this.url = url;
    this.clientId = clientId;
    this.topics = topics;
    this.callback = callback;
  }

  boolean isReady() {
    return ready && !ended;
  }

  /** Bytes queued in the WebSocket but not sent yet. */
  long queued() {
    WebSocket socket = ws;
    return socket == null ? 0 : socket.queueSize();
  }

  void connect() {
    Request request = new Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build();
    ws = HTTP.newWebSocket(request, listener);
  }

  /** Publishes {@code payload} to {@code topic} (QoS 0). False when not connected. */
  boolean publish(String topic, byte[] payload) {
    WebSocket socket = ws;
    if (!isReady() || socket == null) return false;
    byte[] t = utf8(topic);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(t, 0, t.length);
    body.write(payload, 0, payload.length);
    return socket.send(ByteString.of(packet(0x30, body.toByteArray())));
  }

  void close() {
    ended = true;
    ready = false;
    if (pinger != null) pinger.cancel(false);
    WebSocket socket = ws;
    if (socket != null) {
      socket.send(ByteString.of(new byte[] {(byte) 0xE0, 0x00})); // DISCONNECT
      socket.close(1000, "bye");
    }
  }

  // ------------------------------------------------------------------ protocol

  private final WebSocketListener listener =
      new WebSocketListener() {
        @Override
        public void onOpen(WebSocket socket, Response response) {
          ByteArrayOutputStream vh = new ByteArrayOutputStream();
          byte[] proto = utf8("MQTT");
          vh.write(proto, 0, proto.length);
          vh.write(4); // protocol level 3.1.1
          vh.write(0x02); // clean session
          vh.write(KEEP_ALIVE_S >> 8);
          vh.write(KEEP_ALIVE_S & 0xFF);
          byte[] id = utf8(clientId);
          vh.write(id, 0, id.length);
          socket.send(ByteString.of(packet(0x10, vh.toByteArray())));
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
          byte[] data = bytes.toByteArray();
          synchronized (inbox) {
            inbox.write(data, 0, data.length);
            drain();
          }
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
          lost("closed " + code + " " + reason);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable t, Response response) {
          lost(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
      };

  /** Splits the received bytes into MQTT packets (a frame may hold several, or half of one). */
  private void drain() {
    byte[] all = inbox.toByteArray();
    int pos = 0;
    while (pos < all.length) {
      int start = pos;
      int header = all[pos++] & 0xFF;
      int length = 0;
      int multiplier = 1;
      boolean complete = false;
      while (pos < all.length) {
        int b = all[pos++] & 0xFF;
        length += (b & 0x7F) * multiplier;
        multiplier *= 128;
        if ((b & 0x80) == 0) {
          complete = true;
          break;
        }
      }
      if (!complete || pos + length > all.length) {
        pos = start; // wait for the rest
        break;
      }
      byte[] body = new byte[length];
      System.arraycopy(all, pos, body, 0, length);
      pos += length;
      handle(header, body);
    }
    inbox.reset();
    inbox.write(all, pos, all.length - pos);
  }

  private void handle(int header, byte[] body) {
    switch (header >> 4) {
      case 2: // CONNACK
        if (body.length >= 2 && body[1] == 0) {
          subscribe();
        } else {
          lost("broker refused (" + (body.length >= 2 ? body[1] : -1) + ")");
        }
        break;
      case 9: // SUBACK
        ready = true;
        pinger =
            TIMER.scheduleAtFixedRate(
                () -> {
                  WebSocket socket = ws;
                  if (socket != null && !ended) {
                    socket.send(ByteString.of(new byte[] {(byte) 0xC0, 0x00})); // PINGREQ
                  }
                },
                KEEP_ALIVE_S / 2,
                KEEP_ALIVE_S / 2,
                TimeUnit.SECONDS);
        callback.onReady(this);
        break;
      case 3: // PUBLISH
        if (body.length < 2) return;
        int qos = (header >> 1) & 0x03;
        int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        if (2 + topicLen > body.length) return;
        String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
        int payloadStart = 2 + topicLen + (qos > 0 ? 2 : 0);
        if (payloadStart > body.length) return;
        byte[] payload = new byte[body.length - payloadStart];
        System.arraycopy(body, payloadStart, payload, 0, payload.length);
        callback.onMessage(this, topic, payload);
        break;
      default:
        break; // PINGRESP and the rest
    }
  }

  private void subscribe() {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(0x00);
    body.write(0x01); // packet id 1
    for (String topic : topics) {
      byte[] t = utf8(topic);
      body.write(t, 0, t.length);
      body.write(0x00); // QoS 0
    }
    WebSocket socket = ws;
    if (socket != null) socket.send(ByteString.of(packet(0x82, body.toByteArray())));
  }

  private void lost(String why) {
    if (ended) return;
    ended = true;
    ready = false;
    if (pinger != null) pinger.cancel(false);
    Timber.w("MQTT %s lost: %s", url, why);
    callback.onLost(this, why);
  }

  // ------------------------------------------------------------------ encoding

  private static byte[] packet(int header, byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(header);
    int len = body.length;
    do {
      int digit = len % 128;
      len /= 128;
      if (len > 0) digit |= 0x80;
      out.write(digit);
    } while (len > 0);
    out.write(body, 0, body.length);
    return out.toByteArray();
  }

  /** MQTT string: 2-byte length + UTF-8 bytes. */
  private static byte[] utf8(String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[b.length + 2];
    out[0] = (byte) (b.length >> 8);
    out[1] = (byte) (b.length & 0xFF);
    System.arraycopy(b, 0, out, 2, b.length);
    return out;
  }
}
