import { requireNativeModule } from "expo-modules-core";
const VideoEffectsNativeModule = requireNativeModule("VideoEffectsSdkReactNativeModule");
class TsvbVideoEffects {
    _state = {
        isInitialized: false,
        isReady: false,
        activeEffect: "none",
        isEffectsUnavailable: false,
        error: null,
    };
    _subscribers = new Set();
    _frameCaptureSubscription = null;
    // Field exists solely to retain the subscription — Hermes can GC the
    // callback in release builds if the EventSubscription object isn't held.
    // @ts-expect-error -- intentionally unread; presence keeps the listener alive.
    _nativeLogSubscription;
    // Pending log events received before any consumer subscribed.
    // Native emits can fire during module OnCreate — earlier than the
    // first `subscribe()` call — and would otherwise be lost. Capped to
    // avoid unbounded growth if no consumer ever registers.
    _pendingLogEvents = [];
    static PENDING_LOG_CAP = 128;
    constructor() {
        // Permanent listener — native log events flow regardless of consumer subscriptions.
        // Forwarded to subscribers as { type: 'log', log: ... } so the app's logger can
        // pick them up and route to its own backend (DataDog, console, etc.).
        this._nativeLogSubscription = VideoEffectsNativeModule.addListener("onTsvbLog", log => {
            this.emit({ type: "log", log });
        });
    }
    async initialize(config) {
        if (this._state.isEffectsUnavailable) {
            throw new Error(this._state.error ?? "Effects SDK unavailable");
        }
        if (this._state.isInitialized) {
            return { success: true, status: "already_initialized" };
        }
        const { trackId } = config;
        try {
            const result = await VideoEffectsNativeModule.initialize(config.customerID, trackId);
            if (!result.success) {
                const msg = result.error || "Initialization failed";
                this.updateState({ error: msg, isEffectsUnavailable: true });
                throw new Error(msg);
            }
            this.updateState({
                isInitialized: true,
                isReady: true,
                error: null,
            });
            return result;
        }
        catch (error) {
            const msg = `Failed to initialize TSVB SDK: ${error}`;
            if (!this._state.isEffectsUnavailable) {
                this.updateState({ error: msg, isEffectsUnavailable: true });
            }
            throw new Error(msg);
        }
    }
    async enableBlur(options) {
        this.ensureInitialized();
        this.ensureEffectsAvailable();
        try {
            const power = options?.power ?? 0.5;
            await VideoEffectsNativeModule.enableBlurBackground(power);
            this.updateState({ activeEffect: "blur", error: null });
        }
        catch (error) {
            const msg = `Failed to enable blur: ${error}`;
            this.emitError(msg, true);
            throw new Error(msg);
        }
    }
    async enableReplaceBackground(options) {
        this.ensureInitialized();
        this.ensureEffectsAvailable();
        try {
            await VideoEffectsNativeModule.enableReplaceBackground(options.image);
            this.updateState({ activeEffect: "replace", error: null });
        }
        catch (error) {
            const msg = `Failed to enable background replacement: ${error}`;
            this.emitError(msg, true);
            throw new Error(msg);
        }
    }
    async disableEffects() {
        this.ensureInitialized();
        try {
            if (this._state.activeEffect === "blur") {
                await VideoEffectsNativeModule.disableBlurBackground();
            }
            else if (this._state.activeEffect === "replace") {
                await VideoEffectsNativeModule.disableReplaceBackground();
            }
            this.updateState({ activeEffect: "none", error: null });
        }
        catch (error) {
            const msg = `Failed to disable effects: ${error}`;
            this.emitError(msg, true);
            throw new Error(msg);
        }
    }
    getState() {
        return { ...this._state };
    }
    subscribe(callback) {
        this._subscribers.add(callback);
        // Replay any log events that arrived before this consumer subscribed,
        // then drain the buffer so we don't replay them again to future consumers.
        if (this._pendingLogEvents.length > 0) {
            const drained = this._pendingLogEvents;
            this._pendingLogEvents = [];
            drained.forEach(event => {
                try {
                    callback(event);
                }
                catch {
                    // Don't let subscriber errors propagate
                }
            });
        }
        return () => {
            this._subscribers.delete(callback);
        };
    }
    /** Set segmentation quality preset. Only effective on iOS — Android handles this internally. */
    setSegmentationPreset(preset) {
        VideoEffectsNativeModule.setSegmentationPreset(preset);
    }
    /**
     * Start periodic frame capture. Captured frames are saved as JPEG files
     * and emitted via the subscriber callback as `frameCaptured` events.
     * @param intervalMs Capture interval in milliseconds (default: 5000)
     */
    startFrameCapture(intervalMs = 5000) {
        this.ensureInitialized();
        if (!this._frameCaptureSubscription) {
            this._frameCaptureSubscription = VideoEffectsNativeModule.addListener("onFrameCaptured", (event) => {
                this.emit({ type: "frameCaptured", frame: event });
            });
        }
        VideoEffectsNativeModule.startFrameCapture(intervalMs);
    }
    /** Stop periodic frame capture. */
    stopFrameCapture() {
        VideoEffectsNativeModule.stopFrameCapture();
        this._frameCaptureSubscription?.remove();
        this._frameCaptureSubscription = null;
    }
    cleanup() {
        this.stopFrameCapture();
        try {
            VideoEffectsNativeModule.cleanup();
        }
        catch {
            // Ignore cleanup errors
        }
        this._state = {
            isInitialized: false,
            isReady: false,
            activeEffect: "none",
            isEffectsUnavailable: false,
            error: null,
        };
        this.emit({ type: "stateChange", state: this.getState() });
    }
    // --- Private ---
    /** Query native for fallback state and update local state. Returns true if effects are unavailable. */
    checkEffectsAvailability() {
        try {
            const unavailable = VideoEffectsNativeModule.isEffectsUnavailable();
            if (unavailable !== this._state.isEffectsUnavailable) {
                this.updateState({ isEffectsUnavailable: unavailable });
            }
            return unavailable;
        }
        catch {
            return false;
        }
    }
    ensureInitialized() {
        if (!this._state.isInitialized) {
            throw new Error("TSVB SDK is not initialized. Call initialize() first.");
        }
    }
    ensureEffectsAvailable() {
        if (this.checkEffectsAvailability()) {
            throw new Error("Effects unavailable — camera is running in fallback mode without effects pipeline.");
        }
    }
    updateState(partial) {
        this._state = { ...this._state, ...partial };
        this.emit({ type: "stateChange", state: this.getState() });
    }
    emitError(error, recoverable) {
        this.emit({ type: "error", error, recoverable });
    }
    emit(event) {
        if (event.type === "log" && this._subscribers.size === 0) {
            this._pendingLogEvents.push(event);
            if (this._pendingLogEvents.length > TsvbVideoEffects.PENDING_LOG_CAP) {
                this._pendingLogEvents.shift();
            }
            return;
        }
        this._subscribers.forEach(cb => {
            try {
                cb(event);
            }
            catch {
                // Don't let subscriber errors propagate
            }
        });
    }
}
export const tsvbVideoEffects = new TsvbVideoEffects();
export * from "./VideoEffectsSdkReactNativeModule.types";
export { TsvbVideoEffects };
export { VideoEffectsNativeModule as TsvbNativeModule };
//# sourceMappingURL=VideoEffectsSdkReactNativeModule.js.map