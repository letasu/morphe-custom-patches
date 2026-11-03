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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final List<ShortsVisibilityObserver> shortsViews = new ArrayList<>();
    private static volatile boolean hasTrackedShorts = false;
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
                    updateShortsVisibility();
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
                        if (isShortsVisible) SmartAudioFocusManager.onActivityStopped();
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

    // A transient loss does not unregister a request. Keep the exact focus clients
    // until abandonment, including legacy calls on Android 8+ and delayed grants.
    private static final Map<AudioManager, AudioFocusRequest> modernFocusRequests = new IdentityHashMap<>();
    private static final Set<AudioManager> legacyFocusManagers =
            Collections.newSetFromMap(new IdentityHashMap<>());

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
    public static synchronized void onShortsCreate(@Nullable View view) {
        if (view == null) return;
        try {
            for (ShortsVisibilityObserver observer : shortsViews) {
                if (observer.viewRef.get() == view) return;
            }
            registerShortsActivity(view.getContext());
            ShortsVisibilityObserver observer = new ShortsVisibilityObserver(view);
            shortsViews.add(observer);
            hasTrackedShorts = true;
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
        private boolean attached;

        ShortsVisibilityObserver(View view) {
            viewRef = new WeakReference<>(view);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            attached = true;
            if (treeObserver != null && treeObserver.isAlive()) {
                treeObserver.removeOnGlobalLayoutListener(this);
                treeObserver.removeOnPreDrawListener(this);
            }
            treeObserver = view.getViewTreeObserver();
            treeObserver.addOnGlobalLayoutListener(this);
            treeObserver.addOnPreDrawListener(this);
            updateShortsVisibility();
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            // Android clears View.mAttachInfo after invoking detach listeners.
            // Do not query isAttachedToWindow()/isShown() here: both can still be true.
            attached = false;
            if (treeObserver != null && treeObserver.isAlive()) {
                treeObserver.removeOnGlobalLayoutListener(this);
                treeObserver.removeOnPreDrawListener(this);
            }
            treeObserver = null;
            updateShortsVisibility();
        }

        @Override public void onGlobalLayout() { updateShortsVisibility(); }
        @Override public boolean onPreDraw() {
            updateShortsVisibility();
            return true;
        }
    }

    private static synchronized void updateShortsVisibility() {
        boolean attached = false;
        boolean visible = false;
        for (Iterator<ShortsVisibilityObserver> iterator = shortsViews.iterator(); iterator.hasNext();) {
            ShortsVisibilityObserver observer = iterator.next();
            View view = observer.viewRef.get();
            if (view == null) {
                iterator.remove();
            } else if (observer.attached) {
                attached = true;
                visible |= !isShortsInBackground && view.isShown()
                        && view.getGlobalVisibleRect(shortsVisibleBounds);
            }
        }
        isShortsOpen = attached;
        if (visible == isShortsVisible) return;
        isShortsVisible = visible;
        onShortsStateChanged(visible);
    }

    private static boolean shouldSuppressFocus() {
        return isShortsInBackground || (hasTrackedShorts
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

            AudioFocusRequest managedRequest = builder.build();
            int result = audioManager.requestAudioFocus(managedRequest);
            rememberModernRequest(audioManager, managedRequest, result);
            Logger.printDebug(() -> "SmartAudioFocusManager: ensureAudioFocus request result: " + result);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasTransientFocus = true;
                isSessionActive = true;
                userManuallyChangedMedia = false;
            }
        } else {
            int result = audioManager.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            rememberLegacyRequest(audioManager, result);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasTransientFocus = true;
                isSessionActive = true;
                userManuallyChangedMedia = false;
            }
        }
    }

    private static final AudioManager.OnAudioFocusChangeListener focusListener =
            SmartAudioFocusManager::onManagedFocusChange;

    private static synchronized void onManagedFocusChange(int focusChange) {
        // A focus callback already queued before abandonment must not revive a closed session.
        if (modernFocusRequests.isEmpty() && legacyFocusManagers.isEmpty()) return;
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
    }

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
        activeLegacyListener = null;
        userManuallyChangedMedia = false;

        int originalGain = request.getFocusGain();
        Logger.printDebug(() -> "SmartAudioFocusManager: requestAudioFocus intercepted. Original gain: " + originalGain);

        // Wrap transient requests as well: forwarding them would create a second,
        // untracked listener which prevents the previous music app regaining focus.
        int gain = originalGain == AudioManager.AUDIOFOCUS_GAIN
                ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : originalGain;
        AudioFocusRequest.Builder builder = new AudioFocusRequest.Builder(gain)
                .setAudioAttributes(request.getAudioAttributes())
                .setAcceptsDelayedFocusGain(request.acceptsDelayedFocusGain())
                .setWillPauseWhenDucked(request.willPauseWhenDucked())
                .setOnAudioFocusChangeListener(focusListener);

        AudioFocusRequest managedRequest = builder.build();
        int result = audioManager.requestAudioFocus(managedRequest);
        rememberModernRequest(audioManager, managedRequest, result);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasTransientFocus = true;
            isSessionActive = true;
        }
        return result;
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
        activeFocusRequest = null;
        userManuallyChangedMedia = false;

        int hintToUse = (durationHint == AudioManager.AUDIOFOCUS_GAIN)
                ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                : durationHint;

        int result = audioManager.requestAudioFocus(focusListener, streamType, hintToUse);
        rememberLegacyRequest(audioManager, result);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasTransientFocus = true;
            isSessionActive = true;
        }
        return result;
    }

    /**
     * Intercepts AudioManager.abandonAudioFocusRequest(...) on API 26+.
     */
    public static synchronized int abandonAudioFocusRequest(AudioManager audioManager, AudioFocusRequest request) {
        if (!SMART_AUDIO_FOCUS.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return audioManager.abandonAudioFocusRequest(request);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: abandonAudioFocusRequest intercepted. isSessionActive=" + isSessionActive);

        // If user is in watch session or watching Shorts, suppress abandonment
        if (!shouldSuppressFocus() && (isSessionActive || isShortsVisible) && !userManuallyChangedMedia) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Suppressing abandonAudioFocusRequest because media is active");
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        return hasManagedRequests() ? forceAbandonFocus() : audioManager.abandonAudioFocusRequest(request);
    }

    /**
     * Intercepts AudioManager.abandonAudioFocus(...) on API < 26.
     */
    public static synchronized int abandonAudioFocus(AudioManager audioManager, AudioManager.OnAudioFocusChangeListener listener) {
        if (!SMART_AUDIO_FOCUS.get()) {
            return audioManager.abandonAudioFocus(listener);
        }

        Logger.printDebug(() -> "SmartAudioFocusManager: Legacy abandonAudioFocus intercepted. isSessionActive=" + isSessionActive);

        if (!shouldSuppressFocus() && (isSessionActive || isShortsVisible) && !userManuallyChangedMedia) {
            Logger.printDebug(() -> "SmartAudioFocusManager: Suppressing legacy abandonAudioFocus because media is active");
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        return hasManagedRequests() ? forceAbandonFocus() : audioManager.abandonAudioFocus(listener);
    }

    /**
     * Releases audio focus if no media (regular video or Shorts) is currently active.
     */
    public static synchronized void abandonFocusIfHeld() {
        if (!hasManagedRequests()) {
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
    private static synchronized int forceAbandonFocus() {
        Logger.printDebug(() -> "SmartAudioFocusManager: Releasing audio focus now");
        hasTransientFocus = false;
        isSessionActive = false;

        Map<AudioManager, AudioFocusRequest> modern = new IdentityHashMap<>(modernFocusRequests);
        List<AudioManager> legacy = new ArrayList<>(legacyFocusManagers);
        modernFocusRequests.clear();
        legacyFocusManagers.clear();
        int result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        for (Map.Entry<AudioManager, AudioFocusRequest> entry : modern.entrySet()) {
            try {
                if (entry.getKey().abandonAudioFocusRequest(entry.getValue()) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    modernFocusRequests.put(entry.getKey(), entry.getValue());
                    result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
            } catch (Exception e) {
                modernFocusRequests.put(entry.getKey(), entry.getValue());
                result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                Logger.printException(() -> "SmartAudioFocusManager: Failed to abandon modern focus", e);
            }
        }
        for (AudioManager manager : legacy) {
            try {
                if (manager.abandonAudioFocus(legacyFocusListener) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    legacyFocusManagers.add(manager);
                    result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
            } catch (Exception e) {
                legacyFocusManagers.add(manager);
                result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                Logger.printException(() -> "SmartAudioFocusManager: Failed to abandon legacy focus", e);
            }
        }
        return result;
    }

    private static boolean hasManagedRequests() {
        return !modernFocusRequests.isEmpty() || !legacyFocusManagers.isEmpty();
    }

    private static void rememberModernRequest(AudioManager manager, AudioFocusRequest request, int result) {
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            modernFocusRequests.put(manager, request);
            legacyFocusManagers.remove(manager);
        }
    }

    private static void rememberLegacyRequest(AudioManager manager, int result) {
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            legacyFocusManagers.add(manager);
            modernFocusRequests.remove(manager);
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
