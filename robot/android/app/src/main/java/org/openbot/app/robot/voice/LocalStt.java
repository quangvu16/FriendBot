package org.openbot.app.robot.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import org.openbot.app.robot.R;

/**
 * One-shot platform speech recognition for when the Xiaozhi session is offline: the mic tap
 * still hears the driver and the transcript goes through the normal text pipeline (REST brain,
 * then the offline keywords), so "go forward", "turn left" and "grip" keep working without the
 * cloud. Used by the Voice Control and AI Pick pages; the Voice Grab page keeps its own
 * continuous recognizer.
 */
public final class LocalStt {

  /** Callbacks arrive on the main thread. */
  public interface Callback {
    void onText(String text);

    /** Already user-facing (no match / no recognizer / permission). */
    void onError(String message);
  }

  private SpeechRecognizer recognizer;
  private Callback callback;
  private Context context;
  private boolean active;

  /** Starts listening for one utterance; the result arrives through {@code callback}. */
  public void start(Context context, Callback callback) {
    stop();
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      callback.onError(context.getString(R.string.voice_no_speech_recognizer));
      return;
    }
    this.context = context.getApplicationContext();
    this.callback = callback;
    active = true;
    recognizer = SpeechRecognizer.createSpeechRecognizer(context);
    recognizer.setRecognitionListener(listener);
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    recognizer.startListening(intent);
  }

  /** Abandons the current utterance (page exit, mic tapped again, or the caller took over). */
  public void stop() {
    active = false;
    callback = null;
    context = null;
    if (recognizer != null) {
      try {
        recognizer.cancel();
        recognizer.destroy();
      } catch (RuntimeException ignored) {
        // the speech service went away with the utterance; nothing else to release
      }
      recognizer = null;
    }
  }

  private void finishText(String text) {
    if (!active) return;
    Callback cb = callback;
    String noMatch = context.getString(R.string.voice_no_match);
    stop();
    if (cb == null) return;
    if (text != null && !text.trim().isEmpty()) cb.onText(text.trim());
    else cb.onError(noMatch);
  }

  private void finishError(int error) {
    if (!active) return;
    Callback cb = callback;
    Context ctx = context;
    stop();
    if (cb == null || ctx == null) return;
    int res;
    switch (error) {
      case SpeechRecognizer.ERROR_NO_MATCH:
      case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
        res = R.string.voice_no_match;
        break;
      case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
        res = R.string.voice_mic_permission_denied;
        break;
      default:
        res = R.string.voice_no_speech_recognizer;
    }
    cb.onError(ctx.getString(res));
  }

  private final RecognitionListener listener =
      new RecognitionListener() {
        @Override
        public void onResults(Bundle results) {
          ArrayList<String> texts =
              results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
          finishText(texts == null || texts.isEmpty() ? null : texts.get(0));
        }

        @Override
        public void onError(int error) {
          finishError(error);
        }

        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onPartialResults(Bundle partialResults) {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
      };
}
