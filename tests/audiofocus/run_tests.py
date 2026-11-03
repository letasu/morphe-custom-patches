"""Compile the real manager against Android fakes and exercise its lifecycle.

Usage: python tests/audiofocus/run_tests.py --java-home /path/to/jdk
No Android SDK or third-party Python packages are required. These tests check
focus ownership and callbacks; external music app behavior needs device testing.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile


SOURCES = {
    "android/content/Context.java": """
package android.content;
public class Context {
    public static final String AUDIO_SERVICE = "audio";
    public static final android.media.AudioManager AUDIO = new android.media.AudioManager();
    public Object getSystemService(String name) { return AUDIO; }
}
""",
    "android/content/ContextWrapper.java": """
package android.content;
public class ContextWrapper extends Context {
    private final Context base;
    public ContextWrapper(Context base) { this.base = base; }
    public Context getBaseContext() { return base; }
}
""",
    "android/app/Activity.java": """
package android.app;
public class Activity extends android.content.ContextWrapper {
    private final Application application;
    public boolean changingConfigurations;
    public Activity(Application app) { super(app); application = app; }
    public Application getApplication() { return application; }
    public boolean isChangingConfigurations() { return changingConfigurations; }
}
""",
    "android/app/Application.java": """
package android.app;
import android.os.Bundle;
public class Application extends android.content.Context {
    public ActivityLifecycleCallbacks callbacks;
    public int registrations;
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) {
        callbacks = c; registrations++;
    }
    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity a, Bundle b);
        void onActivityStarted(Activity a);
        void onActivityResumed(Activity a);
        void onActivityPaused(Activity a);
        void onActivityStopped(Activity a);
        void onActivitySaveInstanceState(Activity a, Bundle b);
        void onActivityDestroyed(Activity a);
    }
}
""",
    "android/os/Bundle.java": "package android.os; public class Bundle {}",
    "android/os/Build.java": """
package android.os;
public class Build {
    public static class VERSION { public static int SDK_INT = 26; }
    public static class VERSION_CODES { public static final int O = 26; }
}
""",
    "android/view/View.java": """
package android.view;
import android.content.Context;
public class View {
    private final Context context;
    private OnAttachStateChangeListener listener;
    public boolean attached;
    public boolean shown = true, onScreen = true;
    private final ViewTreeObserver observer = new ViewTreeObserver();
    public View(Context context) { this.context = context; }
    public Context getContext() { return context; }
    public boolean isAttachedToWindow() { return attached; }
    public boolean isShown() { return shown; }
    public boolean getGlobalVisibleRect(android.graphics.Rect rect) { return onScreen; }
    public ViewTreeObserver getViewTreeObserver() { return observer; }
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { listener = l; }
    public void attach() { attached = true; listener.onViewAttachedToWindow(this); }
    public void detach() { attached = false; listener.onViewDetachedFromWindow(this); }
    // Android can still report attachment from inside the detach callback.
    public void detachBeforeClearingAttachment() { listener.onViewDetachedFromWindow(this); attached = false; }
    public interface OnAttachStateChangeListener {
        void onViewAttachedToWindow(View view);
        void onViewDetachedFromWindow(View view);
    }
}
""",
    "android/graphics/Rect.java": "package android.graphics; public class Rect {}",
    "android/view/ViewTreeObserver.java": """
package android.view;
import java.util.ArrayList;
public class ViewTreeObserver {
    public interface OnGlobalLayoutListener { void onGlobalLayout(); }
    public interface OnPreDrawListener { boolean onPreDraw(); }
    private final ArrayList<OnGlobalLayoutListener> layouts = new ArrayList<>();
    private final ArrayList<OnPreDrawListener> draws = new ArrayList<>();
    public boolean isAlive() { return true; }
    public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) { layouts.add(l); }
    public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l) { layouts.remove(l); }
    public void addOnPreDrawListener(OnPreDrawListener l) { draws.add(l); }
    public void removeOnPreDrawListener(OnPreDrawListener l) { draws.remove(l); }
    public void layout() { for (var l : new ArrayList<>(layouts)) l.onGlobalLayout(); }
    public void draw() {
        for (var l : new ArrayList<>(draws)) {
            if (!l.onPreDraw()) throw new AssertionError("Rendering must not be cancelled");
        }
    }
}
""",
    "android/media/AudioAttributes.java": """
package android.media;
public class AudioAttributes {
    public static final int USAGE_MEDIA = 1, CONTENT_TYPE_MUSIC = 2;
    public static class Builder {
        public Builder setUsage(int value) { return this; }
        public Builder setContentType(int value) { return this; }
        public AudioAttributes build() { return new AudioAttributes(); }
    }
}
""",
    "android/media/AudioFocusRequest.java": """
package android.media;
public class AudioFocusRequest {
    private int gain;
    private AudioAttributes attributes = new AudioAttributes();
    private AudioManager.OnAudioFocusChangeListener mFocusListener;
    public int getFocusGain() { return gain; }
    public AudioAttributes getAudioAttributes() { return attributes; }
    public boolean acceptsDelayedFocusGain() { return false; }
    public boolean willPauseWhenDucked() { return false; }
    public void dispatchFocusChange(int change) { if (mFocusListener != null) mFocusListener.onAudioFocusChange(change); }
    public static class Builder {
        private final AudioFocusRequest request = new AudioFocusRequest();
        public Builder(int gain) { request.gain = gain; }
        public Builder setAudioAttributes(AudioAttributes a) { request.attributes = a; return this; }
        public Builder setAcceptsDelayedFocusGain(boolean v) { return this; }
        public Builder setWillPauseWhenDucked(boolean v) { return this; }
        public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener l) {
            request.mFocusListener = l; return this;
        }
        public AudioFocusRequest build() { return request; }
    }
}
""",
    "android/media/AudioManager.java": """
package android.media;
public class AudioManager {
    public static final int AUDIOFOCUS_GAIN = 1, AUDIOFOCUS_GAIN_TRANSIENT = 2,
        AUDIOFOCUS_LOSS = -1, AUDIOFOCUS_LOSS_TRANSIENT = -2,
        AUDIOFOCUS_REQUEST_GRANTED = 1, AUDIOFOCUS_REQUEST_FAILED = 0, AUDIOFOCUS_REQUEST_DELAYED = 2, STREAM_MUSIC = 3;
    public int requests, releases;
    public Object owner;
    public int nextResult = AUDIOFOCUS_REQUEST_GRANTED;
    public Object lastOwner;
    public interface OnAudioFocusChangeListener { void onAudioFocusChange(int value); }
    public int requestAudioFocus(AudioFocusRequest r) {
        requests++; owner = lastOwner = r; int result = nextResult; nextResult = 1; return result;
    }
    public int requestAudioFocus(OnAudioFocusChangeListener l, int s, int h) {
        requests++; owner = lastOwner = l; return 1;
    }
    public void dispatchFocusChange(int change) {
        if (lastOwner instanceof AudioFocusRequest r) r.dispatchFocusChange(change);
        else if (lastOwner instanceof OnAudioFocusChangeListener l) l.onAudioFocusChange(change);
    }
    public int abandonAudioFocusRequest(AudioFocusRequest r) {
        if (owner == r) { owner = null; releases++; } return 1;
    }
    public int abandonAudioFocus(OnAudioFocusChangeListener l) {
        if (owner == l) { owner = null; releases++; } return 1;
    }
}
""",
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "kotlin/Unit.java": "package kotlin; public class Unit { public static final Unit INSTANCE = new Unit(); }",
    "app/morphe/extension/shared/Logger.java": """
package app.morphe.extension.shared;
import java.util.function.Supplier;
public class Logger {
    public static void printDebug(Supplier<String> message) {}
    public static void printException(Supplier<String> message, Throwable t) { throw new AssertionError(message.get(), t); }
}
""",
    "app/morphe/extension/shared/Utils.java": """
package app.morphe.extension.shared;
public class Utils {
    public static android.content.Context getContext() { return new android.content.Context(); }
}
""",
    "app/morphe/extension/shared/settings/BooleanSetting.java": """
package app.morphe.extension.shared.settings;
public class BooleanSetting {
    public boolean value;
    public BooleanSetting(String key, boolean defaultValue, boolean restart) { value = defaultValue; }
    public boolean get() { return value; }
}
""",
    "app/morphe/extension/youtube/shared/Event.java": """
package app.morphe.extension.youtube.shared;
import java.util.function.Function;
public class Event<T> {
    private Function<T, kotlin.Unit> observer;
    public void addObserver(Function<T, kotlin.Unit> value) { observer = value; }
    public void emit(T value) { observer.apply(value); }
}
""",
    "app/morphe/extension/youtube/shared/PlayerType.java": """
package app.morphe.extension.youtube.shared;
public enum PlayerType {
    NONE, WATCH_WHILE_MINIMIZED, WATCH_WHILE_MAXIMIZED,
    WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED, WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED;
    public static PlayerType current = NONE;
    private static final Event<PlayerType> EVENT = new Event<>();
    public static Event<PlayerType> getOnChange() { return EVENT; }
    public static PlayerType getCurrent() { return current; }
    public boolean isMaximizedOrFullscreen() { return this == WATCH_WHILE_MAXIMIZED; }
}
""",
    "app/morphe/extension/youtube/shared/VideoState.java": """
package app.morphe.extension.youtube.shared;
public enum VideoState {
    PLAYING, PAUSED, ENDED;
    private static final Event<VideoState> EVENT = new Event<>();
    public static Event<VideoState> getOnChange() { return EVENT; }
    public static VideoState getCurrent() { return PLAYING; }
}
""",
    "FocusLifecycleTest.java": """
import android.app.*;
import android.content.*;
import android.media.*;
import android.view.View;
import app.morphe.extension.youtube.patches.audiofocus.SmartAudioFocusManager;
import app.morphe.extension.youtube.shared.*;

public class FocusLifecycleTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String scenario = args[0];
        boolean legacy = scenario.equals("legacy");
        if (legacy) android.os.Build.VERSION.SDK_INT = 25;
        Application app = new Application();
        Activity host = new Activity(app);
        View view = new View(new ContextWrapper(host));
        AudioManager audio = Context.AUDIO;
        AudioFocusRequest original = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build();
        AudioManager.OnAudioFocusChangeListener listener = change -> {};
        if (scenario.equals("disabled")) SmartAudioFocusManager.SMART_AUDIO_FOCUS.value = false;
        if (scenario.equals("alreadyAttached")) view.attached = true;
        if (scenario.equals("legacyOnModern")) {
            SmartAudioFocusManager.requestAudioFocus(audio, listener, 3, 1);
        }
        SmartAudioFocusManager.onShortsCreate(view);
        if (!view.attached) view.attach();
        check(app.registrations == 1, "Lifecycle callback must be registered once");

        if (scenario.equals("disabled")) {
            SmartAudioFocusManager.requestAudioFocus(audio, original);
            app.callbacks.onActivityStopped(host);
            check(audio.owner == original && audio.releases == 0, "Disabled setting must preserve original focus");
            return;
        }
        check(audio.owner != null, "Opening Shorts must request focus");
        SmartAudioFocusManager.onShortsCreate(view);
        check(app.registrations == 1, "Repeated hook must not duplicate observers");
        if (scenario.equals("detachWhileAttached")) {
            view.detachBeforeClearingAttachment();
            check(audio.owner == null, "Detach callback must release focus before Android clears attachment");
            return;
        }
        if (scenario.equals("precreatedView")) {
            View next = new View(host);
            SmartAudioFocusManager.onShortsCreate(next);
            view.detach();
            check(audio.owner == null, "A not-yet-attached next view must not hide the active view's detach");
            return;
        }
        if (scenario.equals("overlappingViews")) {
            View next = new View(host);
            SmartAudioFocusManager.onShortsCreate(next);
            next.attach();
            next.detach();
            check(audio.owner != null, "Detaching one overlapping Shorts player must retain focus for the other");
            view.detachBeforeClearingAttachment();
            check(audio.owner == null, "Detaching the last visible Shorts player must release focus");
            return;
        }
        if (scenario.equals("transientRequest")) {
            SmartAudioFocusManager.requestAudioFocus(audio, new AudioFocusRequest.Builder(2).build());
            view.detach();
            check(audio.owner == null, "Already-transient native requests must also be released");
            return;
        }
        if (scenario.equals("legacyOnModern")) {
            view.detach();
            check(audio.owner == null, "Legacy requests on modern Android must release the registered listener");
            return;
        }
        if (scenario.equals("mixedAbandon")) {
            audio.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS);
            SmartAudioFocusManager.abandonAudioFocus(audio, listener);
            check(audio.owner == null, "A legacy abandon call must release a modern wrapped request");
            return;
        }
        if (scenario.equals("transientLoss")) {
            audio.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            view.detachBeforeClearingAttachment();
            check(audio.owner == null, "A temporarily lost focus request must still be abandoned on exit");
            return;
        }
        if (scenario.equals("delayedRequest")) {
            audio.nextResult = AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
            check(SmartAudioFocusManager.requestAudioFocus(audio, original) == 2, "Simulate a pending native request");
            view.detachBeforeClearingAttachment();
            check(audio.owner == null, "Pending focus requests must be cancelled when leaving Shorts");
            audio.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            view.attach();
            check(audio.owner != null, "A queued old gain callback must not suppress focus acquisition on return");
            return;
        }
        if (scenario.equals("multipleManagers")) {
            AudioManager second = new AudioManager();
            SmartAudioFocusManager.requestAudioFocus(second, original);
            view.detach();
            check(audio.owner == null && second.owner == null, "Every AudioManager focus client must be released");
            return;
        }
        if (scenario.equals("channel") || scenario.equals("offscreen")) {
            if (scenario.equals("channel")) view.shown = false;
            else view.onScreen = false;
            view.getViewTreeObserver().layout();
            view.getViewTreeObserver().draw();
            check(view.attached && audio.owner == null, "Leaving Shorts must release focus even while attached");
            int requests = audio.requests;
            VideoState.getOnChange().emit(VideoState.PLAYING);
            SmartAudioFocusManager.ensureAudioFocus();
            check(SmartAudioFocusManager.requestAudioFocus(audio, original) == 0, "Hidden Shorts must reject late requests");
            check(SmartAudioFocusManager.requestAudioFocus(audio, listener, 3, 1) == 0, "Hidden Shorts must reject legacy requests");
            check(audio.requests == requests, "Channel page must not pause music again");
            app.callbacks.onActivityStopped(host);
            app.callbacks.onActivityStarted(host);
            check(audio.owner == null, "Returning to the channel page must not reclaim focus");
            view.shown = view.onScreen = true;
            view.getViewTreeObserver().draw();
            check(audio.owner != null, "Returning from channel to Shorts must reclaim focus");
            view.getViewTreeObserver().layout();
            check(audio.requests == requests + 1, "Unchanged layout must not request focus again");
            return;
        }
        if (scenario.equals("foreground")) {
            app.callbacks.onActivityPaused(host);
            VideoState.getOnChange().emit(VideoState.PAUSED);
            SmartAudioFocusManager.abandonAudioFocusRequest(audio, original);
            check(audio.owner != null && audio.releases == 0, "Pause must retain focus");
            app.callbacks.onActivityStopped(new Activity(app));
            check(audio.owner != null, "Unrelated activity must not release focus");
            host.changingConfigurations = true;
            app.callbacks.onActivityStopped(host);
            check(audio.owner != null, "Configuration change must not release focus");
            host.changingConfigurations = false;
            view.detach();
            check(audio.owner == null, "Closing Shorts must still release focus");
            return;
        }
        if (scenario.equals("regular")) {
            view.detach();
            PlayerType.current = PlayerType.WATCH_WHILE_MAXIMIZED;
            VideoState.getOnChange().emit(VideoState.PLAYING);
            app.callbacks.onActivityStopped(host);
            check(audio.owner != null, "Regular background playback must remain unchanged");
            return;
        }
        app.callbacks.onActivityStopped(host);
        check(audio.owner == null && audio.releases == 1, "Background Shorts must release actual focus owner");
        int requests = audio.requests;
        VideoState.getOnChange().emit(VideoState.PLAYING);
        SmartAudioFocusManager.ensureAudioFocus();
        if (!legacy) check(SmartAudioFocusManager.requestAudioFocus(audio, original) == 0, "Late modern request must fail");
        check(SmartAudioFocusManager.requestAudioFocus(audio, listener, 3, 1) == 0, "Late legacy request must fail");
        check(audio.requests == requests && audio.owner == null, "Background callbacks must not reclaim focus");
        if (scenario.equals("detached")) view.detach();
        app.callbacks.onActivityStarted(host);
        if (scenario.equals("detached")) {
            check(audio.owner == null, "Returning without Shorts must not reclaim focus");
            view.attach();
        }
        check(audio.owner != null, "Returning to Shorts must reclaim focus");
        check(app.registrations == 1, "Lifecycle callback must not be duplicated");
        app.callbacks.onActivityStopped(host);
        check(audio.owner == null, "Second background transition must also release focus");
    }
}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    manager = root / "extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/audiofocus/SmartAudioFocusManager.java"
    build_dir = (root / "build").resolve()
    build_dir.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="morphe-audiofocus-", dir=build_dir) as folder:
        temp = Path(folder)
        assert temp.resolve().is_relative_to(build_dir)
        sources = []
        for name, source in SOURCES.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            sources.append(str(path))
        subprocess.run([str(args.java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(temp), *sources, str(manager)], check=True)
        for scenario in ("background", "legacy", "foreground", "regular", "disabled", "alreadyAttached", "detached", "channel", "offscreen",
                         "detachWhileAttached", "precreatedView", "overlappingViews", "transientRequest", "legacyOnModern", "mixedAbandon", "multipleManagers",
                         "transientLoss", "delayedRequest"):
            subprocess.run([str(args.java_home / "bin/java"), "-cp", str(temp), "FocusLifecycleTest", scenario], check=True)
            print(f"PASS: {scenario}", flush=True)


if __name__ == "__main__":
    main()
