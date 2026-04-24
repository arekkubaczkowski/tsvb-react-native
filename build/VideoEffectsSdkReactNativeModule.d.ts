import type { NativeModule } from "expo-modules-core/build/ts-declarations/NativeModule";
import type { BlurOptions, EffectsConfig, EffectsEvent, EffectsState, InitializationResult, NativeModuleEventsMap, NativeModuleInterface, ReplaceOptions, SegmentationPreset } from "./VideoEffectsSdkReactNativeModule.types";
declare const VideoEffectsNativeModule: NativeModule<NativeModuleEventsMap> & NativeModuleInterface;
declare class TsvbVideoEffects {
    private _state;
    private _subscribers;
    private _frameCaptureSubscription;
    initialize(config: EffectsConfig): Promise<InitializationResult>;
    enableBlur(options?: BlurOptions): Promise<void>;
    enableReplaceBackground(options: ReplaceOptions): Promise<void>;
    disableEffects(): Promise<void>;
    getState(): EffectsState;
    subscribe(callback: (event: EffectsEvent) => void): () => void;
    /** Set segmentation quality preset. Only effective on iOS — Android handles this internally. */
    setSegmentationPreset(preset: SegmentationPreset): void;
    /**
     * Start periodic frame capture. Captured frames are saved as JPEG files
     * and emitted via the subscriber callback as `frameCaptured` events.
     * @param intervalMs Capture interval in milliseconds (default: 5000)
     */
    startFrameCapture(intervalMs?: number): void;
    /** Stop periodic frame capture. */
    stopFrameCapture(): void;
    cleanup(): void;
    /** Query native for fallback state and update local state. Returns true if effects are unavailable. */
    checkEffectsAvailability(): boolean;
    private ensureInitialized;
    private ensureEffectsAvailable;
    private updateState;
    private emitError;
    private emit;
}
export declare const tsvbVideoEffects: TsvbVideoEffects;
export * from "./VideoEffectsSdkReactNativeModule.types";
export { TsvbVideoEffects };
export { VideoEffectsNativeModule as TsvbNativeModule };
//# sourceMappingURL=VideoEffectsSdkReactNativeModule.d.ts.map