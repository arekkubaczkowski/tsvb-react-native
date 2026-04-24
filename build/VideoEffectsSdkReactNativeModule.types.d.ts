import type { ImageResolvedAssetSource } from "react-native";
export type EffectType = "blur" | "replace" | "none";
export interface EffectsConfig {
    customerID: string;
    trackId: string;
}
export interface BlurOptions {
    /** Blur intensity 0.0 - 1.0. Default: 0.5 */
    power?: number;
}
export interface ReplaceOptions {
    /** Image source: require() asset, {uri: 'https://...'}, or {uri: 'file://...'} */
    image: ImageResolvedAssetSource | {
        uri: string;
    };
}
export interface EffectsState {
    isInitialized: boolean;
    isReady: boolean;
    activeEffect: EffectType;
    /** True when Android pipeline failed and camera is using standard capturer. Effects unavailable for this session. */
    isEffectsUnavailable: boolean;
    error: string | null;
}
export interface FrameCaptureEvent {
    /** Absolute file path to the captured JPEG image */
    filePath: string;
    /** Timestamp in milliseconds when the frame was captured */
    timestamp: number;
    /** Frame width in pixels */
    width: number;
    /** Frame height in pixels */
    height: number;
}
export type EffectsEvent = {
    type: "stateChange";
    state: EffectsState;
} | {
    type: "error";
    error: string;
    recoverable: boolean;
} | {
    type: "frameCaptured";
    frame: FrameCaptureEvent;
};
export interface InitializationResult {
    success: boolean;
    status?: string;
    error?: string;
}
/** Segmentation quality preset. Only effective on iOS — Android SDK handles this internally. */
export type SegmentationPreset = "quality" | "balanced" | "speed" | "lightning";
export type NativeModuleEventsMap = {
    onFrameCaptured(event: FrameCaptureEvent): void;
};
export interface NativeModuleInterface {
    initialize(customerID: string, trackId: string): Promise<InitializationResult>;
    enableBlurBackground(power?: number): Promise<void>;
    disableBlurBackground(): Promise<void>;
    enableReplaceBackground(imagePath?: ImageResolvedAssetSource | {
        uri: string;
    } | null): Promise<void>;
    disableReplaceBackground(): Promise<void>;
    isBlurEnabled(): boolean;
    hasVirtualBackground(): boolean;
    isInitialized(): boolean;
    isEffectsUnavailable(): boolean;
    setSegmentationPreset(preset: string): void;
    startFrameCapture(intervalMs: number): void;
    stopFrameCapture(): void;
    cleanup(): void;
}
//# sourceMappingURL=VideoEffectsSdkReactNativeModule.types.d.ts.map