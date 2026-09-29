package app.morphe.extension.youtube.patches.audiofocus;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;
import kotlin.Unit;

/**
 * Smart Audio Focus Manager:
 * Manages audio focus transitions to temporarily pause external media (Apple Music, Spotify, etc.)
 * during YouTube video playback and automatically resume it when leaving the video watch session.
 */
public final class SmartAudioFocusManager {

    private static volatile boolean initialized = false;

    // Track state of current watch session
    private static volatile boolean isSessionActive = false;
    private static volatile boolean hasTransientFocus = false;
    private static volatile boolean userManuallyChangedMedia = false;

    // Cached references
    private static WeakReference<AudioManager> activeAudioManagerRef = new WeakReference<>(null);
    private static Object activeFocusRequest = null; // AudioFocusRequest on API 26+
    private static AudioManager.OnAudioFocusChangeListener activeLegacyListener = null; // on API < 26

    // Wrapped request for API 26+
    private static AudioFocusRequest wrappedFocusRequest = null;

    static {
        try {
            initialize();
        } catch (Throwable ignored) {}
    }

    private SmartAudioFocusManager() {}

    /**
     * Initializes listeners for player type and video state.
     */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        Logger.printDebug(() -> "SmartAudioFocusManager: Initializing listeners");

        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            onPlayerTypeChanged(type);
            return Unit.INSTANCE;
        });

        VideoState.getOnChange().addObserver((VideoState state) -> {
            onVideoStateChanged(state);
            return Unit.INSTANCE;
        });
    }

    private static void onPlayerTypeChanged(PlayerType newType) {
        if (!Settings.SMART_AUDIO_FOCUS.get()) {
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: PlayerType changed to: " + newType);

        if (newType.isMaximizedOrFullscreen() || newType == PlayerType.WATCH_WHILE_MINIMIZED) {
            isSessionActive = true;
            userManuallyChangedMedia = false;
            if (VideoState.getCurrent() == VideoState.PLAYING) {
                ensureAudioFocus();
            }
        } else if (newType == PlayerType.NONE
                || newType == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED
                || newType == PlayerType.WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Watch session ended (player dismissed)");
            isSessionActive = false;
            abandonFocusIfHeld();
        }
    }

    private static void onVideoStateChanged(VideoState newState) {
        if (!Settings.SMART_AUDIO_FOCUS.get()) {
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: VideoState changed to: " + newState);

        if (newState == VideoState.PLAYING) {
            isSessionActive = true;
            userManuallyChangedMedia = false;
            ensureAudioFocus();
        } else if (newState == VideoState.PAUSED) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Video paused, retaining audio focus for session");
        } else if (newState == VideoState.ENDED) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Video ended");
        }
    }

    private static AudioManager getAudioManager() {
        AudioManager am = activeAudioManagerRef.get();
        if (am != null) {
            return am;
        }
        try {
            Context ctx = Utils.getContext();
            if (ctx != null) {
                am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    activeAudioManagerRef = new WeakReference<>(am);
                    return am;
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SmartAudioFocusManager: getAudioManager failed", t);
        }
        return null;
    }

    /**
     * Ensures audio focus is held with transient gain, pausing external music.
     * Guaranteed to be called on EVERY video play, even if ExoPlayer doesn't re-request focus.
     */
    public static synchronized void ensureAudioFocus() {
        if (hasTransientFocus || userManuallyChangedMedia) {
            return;
        }

        AudioManager audioManager = getAudioManager();
        if (audioManager == null) {
            Logger.printDebug(() -> "SmartAudioFocusManager: ensureAudioFocus() - AudioManager not available");
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: ensureAudioFocus() - Requesting transient focus");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = null;
            if (activeFocusRequest instanceof AudioFocusRequest) {
                attributes = ((AudioFocusRequest) activeFocusRequest).getAudioAttributes();
            }
            if (attributes == null) {
                attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
            }

            AudioFocusRequest.Builder builder = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusListener);

            wrappedFocusRequest = builder.build();
            int result = audioManager.requestAudioFocus(wrappedFocusRequest);
            Logger.printDebug(() -> "SmartAudioFocusManager: ensureAudioFocus request result: " + result);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasTransientFocus = true;
                isSessionActive = true;
                userManuallyChangedMedia = false;
            }
        } else {
            int result = audioManager.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasTransientFocus = true;
                isSessionActive = true;
                userManuallyChangedMedia = false;
            }
        }
    }

    private static final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        Logger.printDebug(() -> "SmartAudioFocusManager: onAudioFocusChange: " + focusChange);
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            hasTransientFocus = false;
            if (isSessionActive) {
                userManuallyChangedMedia = true;
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            hasTransientFocus = false;
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            hasTransientFocus = true;
            userManuallyChangedMedia = false;
        }
        forwardFocusChange(focusChange);
    };

    private static final AudioManager.OnAudioFocusChangeListener legacyFocusListener = focusListener;

    private static void forwardFocusChange(int focusChange) {
        try {
            if (activeLegacyListener != null) {
                activeLegacyListener.onAudioFocusChange(focusChange);
            } else if (activeFocusRequest instanceof AudioFocusRequest) {
                AudioManager.OnAudioFocusChangeListener original = extractListener((AudioFocusRequest) activeFocusRequest);
                if (original != null) {
                    original.onAudioFocusChange(focusChange);
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SmartAudioFocusManager: forwardFocusChange failed", t);
        }
    }

    private static AudioManager.OnAudioFocusChangeListener extractListener(AudioFocusRequest request) {
        if (request == null) return null;
        try {
            Field field = AudioFocusRequest.class.getDeclaredField("mFocusListener");
            field.setAccessible(true);
            return (AudioManager.OnAudioFocusChangeListener) field.get(request);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Intercepts AudioManager.requestAudioFocus(...) on API 26+.
     */
    public static int requestAudioFocus(AudioManager audioManager, AudioFocusRequest request) {
        initialize();

        if (!Settings.SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.requestAudioFocus(request);
        }

        activeAudioManagerRef = new WeakReference<>(audioManager);
        activeFocusRequest = request;
        userManuallyChangedMedia = false;

        int originalGain = request.getFocusGain();
        Logger.printDebug(() -> "SmartAudioFocusManager: requestAudioFocus intercepted. Original gain: " + originalGain);

        if (originalGain == AudioManager.AUDIOFOCUS_GAIN) {
            AudioFocusRequest.Builder builder = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(request.getAudioAttributes())
                    .setAcceptsDelayedFocusGain(request.acceptsDelayedFocusGain())
                    .setWillPauseWhenDucked(request.willPauseWhenDucked())
                    .setOnAudioFocusChangeListener(focusListener);

            wrappedFocusRequest = builder.build();
            int result = audioManager.requestAudioFocus(wrappedFocusRequest);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasTransientFocus = true;
                isSessionActive = true;
            }
            return result;
        }

        return audioManager.requestAudioFocus(request);
    }

    /**
     * Intercepts AudioManager.requestAudioFocus(...) on API < 26.
     */
    public static int requestAudioFocus(AudioManager audioManager, AudioManager.OnAudioFocusChangeListener listener,
                                        int streamType, int durationHint) {
        initialize();

        if (!Settings.SMART_AUDIO_FOCUS.get()) {
            return audioManager.requestAudioFocus(listener, streamType, durationHint);
        }

        activeAudioManagerRef = new WeakReference<>(audioManager);
        activeLegacyListener = listener;
        userManuallyChangedMedia = false;

        int hintToUse = (durationHint == AudioManager.AUDIOFOCUS_GAIN)
                ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                : durationHint;

        int result = audioManager.requestAudioFocus(focusListener, streamType, hintToUse);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasTransientFocus = true;
            isSessionActive = true;
        }
        return result;
    }

    /**
     * Intercepts AudioManager.abandonAudioFocusRequest(...) on API 26+.
     */
    public static int abandonAudioFocusRequest(AudioManager audioManager, AudioFocusRequest request) {
        if (!Settings.SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.abandonAudioFocusRequest(request);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: abandonAudioFocusRequest intercepted. isSessionActive=" + isSessionActive);

        // If user is still in watch session, suppress abandonment so external music doesn't resume
        if (isSessionActive && !userManuallyChangedMedia) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Suppressing abandonAudioFocusRequest because session is active");
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        hasTransientFocus = false;
        if (wrappedFocusRequest != null) {
            int result = audioManager.abandonAudioFocusRequest(wrappedFocusRequest);
            wrappedFocusRequest = null;
            return result;
        }
        return audioManager.abandonAudioFocusRequest(request);
    }

    /**
     * Intercepts AudioManager.abandonAudioFocus(...) on API < 26.
     */
    public static int abandonAudioFocus(AudioManager audioManager, AudioManager.OnAudioFocusChangeListener listener) {
        if (!Settings.SMART_AUDIO_FOCUS.get()) {
            return audioManager.abandonAudioFocus(listener);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Legacy abandonAudioFocus intercepted. isSessionActive=" + isSessionActive);

        if (isSessionActive && !userManuallyChangedMedia) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Suppressing legacy abandonAudioFocus because session is active");
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        hasTransientFocus = false;
        return audioManager.abandonAudioFocus(listener);
    }

    /**
     * Releases audio focus when leaving the watch session.
     */
    public static synchronized void abandonFocusIfHeld() {
        if (!hasTransientFocus) {
            return;
        }

        AudioManager audioManager = getAudioManager();
        if (audioManager == null) {
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Releasing audio focus now (abandoning focus)");
        hasTransientFocus = false;
        isSessionActive = false;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (wrappedFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(wrappedFocusRequest);
                    wrappedFocusRequest = null;
                } else if (activeFocusRequest instanceof AudioFocusRequest) {
                    audioManager.abandonAudioFocusRequest((AudioFocusRequest) activeFocusRequest);
                }
            } else if (activeLegacyListener != null) {
                audioManager.abandonAudioFocus(activeLegacyListener);
            }
        } catch (Exception e) {
            Logger.printException(() -> "SmartAudioFocusManager: Failed to abandon audio focus", e);
        }
    }

    /**
     * Called on MainActivity lifecycle events.
     */
    public static void onActivityStopped() {
        if (!Settings.SMART_AUDIO_FOCUS.get()) return;

        Logger.printDebug(() -> "SmartAudioFocusManager: Activity stopped, abandoning focus");
        isSessionActive = false;
        abandonFocusIfHeld();
    }
}
