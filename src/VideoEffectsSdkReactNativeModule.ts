import { requireNativeModule, type EventSubscription } from "expo-modules-core";
import type { NativeModule } from "expo-modules-core/build/ts-declarations/NativeModule";

import type {
  BlurOptions,
  EffectsConfig,
  EffectsEvent,
  EffectsState,
  FrameCaptureEvent,
  InitializationResult,
  NativeModuleEventsMap,
  NativeModuleInterface,
  ReplaceOptions,
  SegmentationPreset,
} from "./VideoEffectsSdkReactNativeModule.types";

const VideoEffectsNativeModule = requireNativeModule<
  NativeModule<NativeModuleEventsMap> & NativeModuleInterface
>("VideoEffectsSdkReactNativeModule");

class TsvbVideoEffects {
  private _state: EffectsState = {
    isInitialized: false,
    isReady: false,
    activeEffect: "none",
    isEffectsUnavailable: false,
    error: null,
  };
  private _subscribers = new Set<(event: EffectsEvent) => void>();
  private _frameCaptureSubscription: EventSubscription | null = null;

  constructor() {
    // Permanent listener — native log events flow regardless of consumer subscriptions.
    // Forwarded to subscribers as { type: 'log', log: ... } so the app's logger can
    // pick them up and route to its own backend (DataDog, console, etc.).
    VideoEffectsNativeModule.addListener("onTsvbLog", log => {
      this.emit({ type: "log", log });
    });
  }

  async initialize(config: EffectsConfig): Promise<InitializationResult> {
    const { trackId } = config;

    try {
      const result = await VideoEffectsNativeModule.initialize(config.customerID, trackId);

      if (!result.success) {
        this.updateState({ error: result.error || "Initialization failed" });
        throw new Error(result.error || "Initialization failed");
      }

      this.updateState({
        isInitialized: true,
        isReady: true,
        error: null,
      });

      return result;
    } catch (error) {
      const msg = `Failed to initialize TSVB SDK: ${error}`;
      this.updateState({ error: msg });
      throw new Error(msg);
    }
  }

  async enableBlur(options?: BlurOptions): Promise<void> {
    this.ensureInitialized();
    this.ensureEffectsAvailable();

    try {
      const power = options?.power ?? 0.5;
      await VideoEffectsNativeModule.enableBlurBackground(power);
      this.updateState({ activeEffect: "blur", error: null });
    } catch (error) {
      const msg = `Failed to enable blur: ${error}`;
      this.emitError(msg, true);
      throw new Error(msg);
    }
  }

  async enableReplaceBackground(options: ReplaceOptions): Promise<void> {
    this.ensureInitialized();
    this.ensureEffectsAvailable();

    try {
      await VideoEffectsNativeModule.enableReplaceBackground(options.image);
      this.updateState({ activeEffect: "replace", error: null });
    } catch (error) {
      const msg = `Failed to enable background replacement: ${error}`;
      this.emitError(msg, true);
      throw new Error(msg);
    }
  }

  async disableEffects(): Promise<void> {
    this.ensureInitialized();

    try {
      if (this._state.activeEffect === "blur") {
        await VideoEffectsNativeModule.disableBlurBackground();
      } else if (this._state.activeEffect === "replace") {
        await VideoEffectsNativeModule.disableReplaceBackground();
      }
      this.updateState({ activeEffect: "none", error: null });
    } catch (error) {
      const msg = `Failed to disable effects: ${error}`;
      this.emitError(msg, true);
      throw new Error(msg);
    }
  }

  getState(): EffectsState {
    return { ...this._state };
  }

  subscribe(callback: (event: EffectsEvent) => void): () => void {
    this._subscribers.add(callback);
    return () => {
      this._subscribers.delete(callback);
    };
  }

  /** Set segmentation quality preset. Only effective on iOS — Android handles this internally. */
  setSegmentationPreset(preset: SegmentationPreset): void {
    VideoEffectsNativeModule.setSegmentationPreset(preset);
  }

  /**
   * Start periodic frame capture. Captured frames are saved as JPEG files
   * and emitted via the subscriber callback as `frameCaptured` events.
   * @param intervalMs Capture interval in milliseconds (default: 5000)
   */
  startFrameCapture(intervalMs: number = 5000): void {
    this.ensureInitialized();

    if (!this._frameCaptureSubscription) {
      this._frameCaptureSubscription = VideoEffectsNativeModule.addListener(
        "onFrameCaptured",
        (event: FrameCaptureEvent) => {
          this.emit({ type: "frameCaptured", frame: event });
        },
      );
    }

    VideoEffectsNativeModule.startFrameCapture(intervalMs);
  }

  /** Stop periodic frame capture. */
  stopFrameCapture(): void {
    VideoEffectsNativeModule.stopFrameCapture();
    this._frameCaptureSubscription?.remove();
    this._frameCaptureSubscription = null;
  }

  cleanup(): void {
    this.stopFrameCapture();

    try {
      VideoEffectsNativeModule.cleanup();
    } catch {
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
  checkEffectsAvailability(): boolean {
    try {
      const unavailable = VideoEffectsNativeModule.isEffectsUnavailable();
      if (unavailable !== this._state.isEffectsUnavailable) {
        this.updateState({ isEffectsUnavailable: unavailable });
      }
      return unavailable;
    } catch {
      return false;
    }
  }

  private ensureInitialized(): void {
    if (!this._state.isInitialized) {
      throw new Error("TSVB SDK is not initialized. Call initialize() first.");
    }
  }

  private ensureEffectsAvailable(): void {
    if (this.checkEffectsAvailability()) {
      throw new Error(
        "Effects unavailable — camera is running in fallback mode without effects pipeline."
      );
    }
  }

  private updateState(partial: Partial<EffectsState>): void {
    this._state = { ...this._state, ...partial };
    this.emit({ type: "stateChange", state: this.getState() });
  }

  private emitError(error: string, recoverable: boolean): void {
    this.emit({ type: "error", error, recoverable });
  }

  private emit(event: EffectsEvent): void {
    this._subscribers.forEach(cb => {
      try {
        cb(event);
      } catch {
        // Don't let subscriber errors propagate
      }
    });
  }
}

export const tsvbVideoEffects = new TsvbVideoEffects();

export * from "./VideoEffectsSdkReactNativeModule.types";
export { TsvbVideoEffects };
export { VideoEffectsNativeModule as TsvbNativeModule };
