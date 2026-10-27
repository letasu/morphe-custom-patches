package app.morphe.extension.youtube.patches.audiofocus;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;
import kotlin.Unit;

/**
 * Smart Audio Focus Manager:
 * Manages audio focus transitions to temporarily pause external media (Apple Music, Spotify, etc.)
 * during YouTube video & Shorts playback and automatically resume it when leaving the video session.
 */
public final class SmartAudioFocusManager {

    public static final BooleanSetting SMART_AUDIO_FOCUS =
            new BooleanSetting("morphe_smart_audio_focus", true, true);

    private static volatile boolean initialized = false;

    // Track state of current watch session
    private static volatile boolean isSessionActive = false;
    private static volatile boolean hasTransientFocus = false;
    private static volatile boolean userManuallyChangedMedia = false;
    private static volatile boolean isShortsOpen = false;
    private static volatile boolean isShortsVisible = false;
    private static volatile boolean isShortsInBackground = false;
    private static volatile WeakReference<View> shortsViewRef = new WeakReference<>(null);
    // Accessed only by synchronized updateShortsVisibility().
    private static final Rect shortsVisibleBounds = new Rect();
    private static WeakReference<Activity> shortsActivityRef = new WeakReference<>(null);
    private static boolean lifecycleCallbacksRegistered = false;

    private static final Application.ActivityLifecycleCallbacks lifecycleCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(Activity activity) {
                    if (activity != shortsActivityRef.get()) return;
                    isShortsInBackground = false;
                    updateShortsVisibility(shortsViewRef.get());
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    if (activity == shortsActivityRef.get()
                            && !activity.isChangingConfigurations() && isShortsVisible) {
                        SmartAudioFocusManager.onActivityStopped();
                    }
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (activity == shortsActivityRef.get()) {
                        shortsActivityRef.clear();
                    }
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityResumed(Activity activity) { }
                @Override public void onActivityPaused(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            };

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

        try {
            Logger.printDebug(() -> "SmartAudioFocusManager: Initializing listeners");

            // Listen for regular player type changes (watch page, miniplayer, dismiss)
            PlayerType.getOnChange().addObserver((PlayerType type) -> {
                onPlayerTypeChanged(type);
                return Unit.INSTANCE;
            });

            // Listen for regular video playback state (play, pause, ended)
            VideoState.getOnChange().addObserver((VideoState state) -> {
                onVideoStateChanged(state);
                return Unit.INSTANCE;
            });
        } catch (Throwable t) {
            Logger.printException(() -> "SmartAudioFocusManager: initialize failed", t);
        }
    }

    /**
     * Injected by bytecode patch into Shorts overlay view creation.
     */
    public static void onShortsCreate(@Nullable View view) {
        if (view == null) return;
        try {
            if (shortsViewRef.get() == view) return;
            registerShortsActivity(view.getContext());
            shortsViewRef = new WeakReference<>(view);
            ShortsVisibilityObserver observer = new ShortsVisibilityObserver(view);
            view.addOnAttachStateChangeListener(observer);
            if (view.isAttachedToWindow()) observer.onViewAttachedToWindow(view);
        } catch (Throwable t) {
            Logger.printException(() -> "SmartAudioFocusManager: onShortsCreate failed", t);
        }
    }

    /**
     * Shorts can stay attached behind a channel page. Observe visibility and bounds,
     * including changes to ancestor views, rather than treating attachment as playback.
     */
    private static final class ShortsVisibilityObserver implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> viewRef;
        private ViewTreeObserver treeObserver;

        ShortsVisibilityObserver(View view) {
            viewRef = new WeakReference<>(view);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            treeObserver = view.getViewTreeObserver();
            treeObserver.addOnGlobalLayoutListener(this);
            treeObserver.addOnPreDrawListener(this);
            updateShortsVisibility(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (treeObserver != null && treeObserver.isAlive()) {
                treeObserver.removeOnGlobalLayoutListener(this);
                treeObserver.removeOnPreDrawListener(this);
            }
            treeObserver = null;
            updateShortsVisibility(view);
        }

        @Override public void onGlobalLayout() { updateShortsVisibility(viewRef.get()); }
        @Override public boolean onPreDraw() {
            updateShortsVisibility(viewRef.get());
            return true;
        }
    }

    private static synchronized void updateShortsVisibility(@Nullable View view) {
        if (view == null || view != shortsViewRef.get()) return;
        isShortsOpen = view.isAttachedToWindow();
        boolean visible = isShortsOpen && !isShortsInBackground
                && view.isShown() && view.getGlobalVisibleRect(shortsVisibleBounds);
        if (visible == isShortsVisible) return;
        isShortsVisible = visible;
        onShortsStateChanged(visible);
    }

    private static boolean shouldSuppressFocus() {
        return isShortsInBackground || (shortsViewRef.get() != null
                && !isShortsVisible && !isRegularVideoActive());
    }

    private static synchronized void registerShortsActivity(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return;
            context = base;
        }
        if (!(context instanceof Activity)) return;

        Activity activity = (Activity) context;
        if (shortsActivityRef.get() != activity) {
            shortsActivityRef = new WeakReference<>(activity);
            isShortsInBackground = false;
        }
        if (!lifecycleCallbacksRegistered) {
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycleCallbacks);
            lifecycleCallbacksRegistered = true;
        }
    }

    private static void onPlayerTypeChanged(PlayerType newType) {
        if (!SMART_AUDIO_FOCUS.get()) {
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
            isSessionActive = false;

            // Do not abandon focus if Shorts player is currently open!
            if (!isShortsVisible) {
                Logger.printDebug(() -> "SmartAudioFocusManager: Watch session ended (player dismissed)");
                abandonFocusIfHeld();
            } else {
                Logger.printDebug(() -> "SmartAudioFocusManager: PlayerType is dismissed, but Shorts is open - keeping focus");
            }
        }
    }

    private static void onVideoStateChanged(VideoState newState) {
        if (!SMART_AUDIO_FOCUS.get()) {
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

    private static void onShortsStateChanged(boolean isOpen) {
        if (!SMART_AUDIO_FOCUS.get()) {
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Shorts open changed to: " + isOpen);

        if (isOpen) {
            userManuallyChangedMedia = false;
            ensureAudioFocus();
        } else {
            // When Shorts player closes, abandon focus if regular video is not playing
            if (!isRegularVideoActive()) {
                Logger.printDebug(() -> "SmartAudioFocusManager: Shorts closed, releasing focus");
                forceAbandonFocus();
            }
        }
    }

    private static boolean isRegularVideoActive() {
        try {
            PlayerType current = PlayerType.getCurrent();
            return (current != null && (current.isMaximizedOrFullscreen() || current == PlayerType.WATCH_WHILE_MINIMIZED))
                    && VideoState.getCurrent() == VideoState.PLAYING;
        } catch (Throwable ignored) {
            return false;
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
     * Works for both regular videos and Shorts.
     */
    public static synchronized void ensureAudioFocus() {
        if (shouldSuppressFocus() || hasTransientFocus || userManuallyChangedMedia) {
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
            if (isSessionActive || isShortsOpen) {
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
    public static synchronized int requestAudioFocus(AudioManager audioManager, AudioFocusRequest request) {
        initialize();

        if (!SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.requestAudioFocus(request);
        }

        // Playback callbacks can arrive after Activity.onStop(). Do not pause music again.
        if (shouldSuppressFocus()) return AudioManager.AUDIOFOCUS_REQUEST_FAILED;

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
    public static synchronized int requestAudioFocus(AudioManager audioManager, AudioManager.OnAudioFocusChangeListener listener,
                                        int streamType, int durationHint) {
        initialize();

        if (!SMART_AUDIO_FOCUS.get()) {
            return audioManager.requestAudioFocus(listener, streamType, durationHint);
        }

        if (shouldSuppressFocus()) return AudioManager.AUDIOFOCUS_REQUEST_FAILED;

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
        if (!SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.abandonAudioFocusRequest(request);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: abandonAudioFocusRequest intercepted. isSessionActive=" + isSessionActive);

        // If user is in watch session or watching Shorts, suppress abandonment
        if (!shouldSuppressFocus() && (isSessionActive || isShortsVisible) && !userManuallyChangedMedia) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Suppressing abandonAudioFocusRequest because media is active");
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
        if (!SMART_AUDIO_FOCUS.get()) {
            return audioManager.abandonAudioFocus(listener);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Legacy abandonAudioFocus intercepted. isSessionActive=" + isSessionActive);

        if (!shouldSuppressFocus() && (isSessionActive || isShortsVisible) && !userManuallyChangedMedia) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Suppressing legacy abandonAudioFocus because media is active");
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        hasTransientFocus = false;
        return audioManager.abandonAudioFocus(listener);
    }

    /**
     * Releases audio focus if no media (regular video or Shorts) is currently active.
     */
    public static synchronized void abandonFocusIfHeld() {
        if (!hasTransientFocus) {
            return;
        }

        // Do not release if Shorts or regular video is still playing
        if (isShortsVisible || isRegularVideoActive()) {
            Logger.printDebug(() -> "SmartAudioFocusManager: abandonFocusIfHeld suppressed because media is still active");
            return;
        }

        forceAbandonFocus();
    }

    /**
     * Unconditionally releases audio focus and resets session state.
     */
    private static synchronized void forceAbandonFocus() {
        AudioManager audioManager = getAudioManager();
        if (audioManager == null) {
            return;
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Releasing audio focus now");
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
            } else {
                audioManager.abandonAudioFocus(legacyFocusListener);
            }
        } catch (Exception e) {
            Logger.printException(() -> "SmartAudioFocusManager: Failed to abandon audio focus", e);
        }
    }

    /**
     * Called on MainActivity lifecycle events.
     */
    public static synchronized void onActivityStopped() {
        if (!SMART_AUDIO_FOCUS.get()) return;

        isShortsInBackground = true;
        isShortsVisible = false;
        Logger.printDebug(() -> "SmartAudioFocusManager: Activity stopped, abandoning focus");
        isSessionActive = false;
        forceAbandonFocus();
    }
}
