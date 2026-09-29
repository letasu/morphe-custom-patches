package app.morphe.extension.youtube.patches.audiofocus;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;
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

    // Cached references for focus abandonment
    private static WeakReference<AudioManager> activeAudioManagerRef = new WeakReference<>(null);
    private static Object activeFocusRequest = null; // AudioFocusRequest on API 26+
    private static AudioManager.OnAudioFocusChangeListener activeLegacyListener = null; // on API < 26

    // Wrapped listener for API 26+
    private static AudioFocusRequest wrappedFocusRequest = null;

    private SmartAudioFocusManager() {}

    /**
     * Initializes listeners for player type and video state.
     * Called once during extension startup.
     */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        Logger.printDebug(() -> "SmartAudioFocusManager: Initializing listeners");

        // Listen for player type changes (navigating between watch page, miniplayer, feeds)
        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            onPlayerTypeChanged(type);
            return Unit.INSTANCE;
        });

        // Listen for video state changes (play, pause, ended)
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

        if (newType.isMaximizedOrFullscreen()) {
            // User is actively in the watch page / video session
            isSessionActive = true;
        } else if (newType.isNoneHiddenOrMinimized() || newType == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED) {
            // User left the watch page (minimized to feed, closed, or navigated away)
            Logger.printDebug(() -> "SmartAudioFocusManager: Watch session ended (left watch page)");
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
        } else if (newState == VideoState.PAUSED) {
            // When paused, we keep the session active and DO NOT abandon focus,
            // so external music remains paused as long as user stays on the video page.
            Logger.printDebug(() -> "SmartAudioFocusManager: Video paused, retaining audio focus for session");
        } else if (newState == VideoState.ENDED) {
            // Video ended. Do not immediately abandon if autoplay may load next video,
            // session will be abandoned if user leaves watch page.
            Logger.printDebug(() -> "SmartAudioFocusManager: Video ended");
        }
    }

    /**
     * Called when YouTube or its player calls AudioManager.requestAudioFocus(...) on API 26+.
     */
    public static int requestAudioFocus(AudioManager audioManager, AudioFocusRequest request) {
        initialize();

        if (!Settings.SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.requestAudioFocus(request);
        }

        activeAudioManagerRef = new WeakReference<>(audioManager);
        activeFocusRequest = request;

        int originalGain = request.getFocusGain();
        Logger.printDebug(() -> "SmartAudioFocusManager: requestAudioFocus intercepted. Original gain: " + originalGain);

        // If request is permanent GAIN, rewrite to GAIN_TRANSIENT so external music app gets LOSS_TRANSIENT
        if (originalGain == AudioManager.AUDIOFOCUS_GAIN) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Rewriting AUDIOFOCUS_GAIN to AUDIOFOCUS_GAIN_TRANSIENT");

            AudioFocusRequest.Builder builder = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(request.getAudioAttributes())
                    .setAcceptsDelayedFocusGain(request.acceptsDelayedFocusGain())
                    .setWillPauseWhenDucked(request.willPauseWhenDucked());

            // Wrap the listener to observe when another app claims focus permanently
            builder.setOnAudioFocusChangeListener(focusChange -> {
                Logger.printDebug(() -> "SmartAudioFocusManager: onAudioFocusChange: " + focusChange);
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    // Another app (e.g. Spotify, Apple Music) was manually played by user
                    Logger.printDebug(() -> "SmartAudioFocusManager: AUDIOFOCUS_LOSS received - user manually played external media");
                    userManuallyChangedMedia = true;
                    hasTransientFocus = false;
                    isSessionActive = false;
                }
            });

            wrappedFocusRequest = builder.build();
            int result = audioManager.requestAudioFocus(wrappedFocusRequest);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasTransientFocus = true;
                isSessionActive = true;
                userManuallyChangedMedia = false;
            }
            return result;
        }

        return audioManager.requestAudioFocus(request);
    }

    /**
     * Called when YouTube or its player calls AudioManager.requestAudioFocus(...) on API < 26.
     */
    public static int requestAudioFocus(AudioManager audioManager, AudioManager.OnAudioFocusChangeListener listener,
                                        int streamType, int durationHint) {
        initialize();

        if (!Settings.SMART_AUDIO_FOCUS.get()) {
            return audioManager.requestAudioFocus(listener, streamType, durationHint);
        }

        activeAudioManagerRef = new WeakReference<>(audioManager);
        activeLegacyListener = listener;

        Logger.printDebug(() -> "SmartAudioFocusManager: Legacy requestAudioFocus intercepted. Hint: " + durationHint);

        int hintToUse = durationHint;
        if (durationHint == AudioManager.AUDIOFOCUS_GAIN) {
            hintToUse = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
        }

        AudioManager.OnAudioFocusChangeListener wrappedListener = focusChange -> {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                userManuallyChangedMedia = true;
                hasTransientFocus = false;
                isSessionActive = false;
            }
            if (listener != null) {
                listener.onAudioFocusChange(focusChange);
            }
        };

        int result = audioManager.requestAudioFocus(wrappedListener, streamType, hintToUse);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasTransientFocus = true;
            isSessionActive = true;
            userManuallyChangedMedia = false;
        }
        return result;
    }

    /**
     * Called when YouTube or its player calls AudioManager.abandonAudioFocusRequest(...) on API 26+.
     */
    public static int abandonAudioFocusRequest(AudioManager audioManager, AudioFocusRequest request) {
        if (!Settings.SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.abandonAudioFocusRequest(request);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: abandonAudioFocusRequest intercepted. isSessionActive=" + isSessionActive);

        // If user is still in watch session (e.g., video was paused or seeking), suppress abandonment
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
     * Called when YouTube or its player calls AudioManager.abandonAudioFocus(...) on API < 26.
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
     * This signals the Android system to notify the previous audio focus owner (Apple Music, Spotify, etc.)
     * with AUDIOFOCUS_GAIN, automatically resuming their playback.
     */
    public static void abandonFocusIfHeld() {
        if (!hasTransientFocus || userManuallyChangedMedia) {
            return;
        }

        AudioManager audioManager = activeAudioManagerRef.get();
        if (audioManager == null) {
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Releasing audio focus now (abandoning focus)");
        hasTransientFocus = false;

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
     * Called on MainActivity lifecycle events (e.g., onDestroy or onStop when background play is disabled).
     */
    public static void onActivityStopped() {
        if (!Settings.SMART_AUDIO_FOCUS.get()) return;

        // If app is closed or stopped, abandon focus
        Logger.printDebug(() -> "SmartAudioFocusManager: Activity stopped, abandoning focus");
        isSessionActive = false;
        abandonFocusIfHeld();
    }
}
