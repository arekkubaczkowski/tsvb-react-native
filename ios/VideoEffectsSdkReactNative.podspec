Pod::Spec.new do |s|
  s.name           = 'VideoEffectsSdkReactNative'
  s.version        = '1.0.0'
  s.summary        = 'React Native wrapper for TSVB Video Effects SDK'
  s.description    = 'Expo module that provides background blur functionality using TSVB Video Effects SDK'
  s.author         = ''
  s.homepage       = 'https://docs.expo.dev/modules/'
  s.platforms      = { :ios => '16.4' }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  
  # WebRTC dependency for video frame processing
  s.dependency 'livekit-react-native-webrtc'

  # Swift/Objective-C compatibility
  s.swift_version = '5.4'

  # Include Swift and Objective-C sources
  s.source_files = "*.swift", "*.h"
  
  # Module configuration
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_EMIT_LOC_STRINGS' => 'YES'
  }
  
  # Local TSVB SDK dependency instead of external
  s.subspec 'TSVB-SDK' do |tsvb|
    tsvb.vendored_frameworks = 'TSVB.xcframework'
    tsvb.preserve_paths = ['TSVB.xcframework', 'TSVB.xcframework.zip']
    tsvb.source_files = 'TSVB_SwiftShim.swift'
  end
  
  # Framework dependencies needed by TSVB
  s.frameworks = 'Foundation', 'UIKit', 'CoreVideo', 'CoreMedia', 'AVFoundation'
  
  # Pre-install hook to extract framework from ZIP
  s.prepare_command = <<-CMD
    if [ ! -d "TSVB.xcframework" ]; then
      echo "🔧 Extracting TSVB.xcframework from ZIP..."
      unzip -q TSVB.xcframework.zip
      echo "✅ TSVB.xcframework extracted successfully"
    else
      echo "ℹ️  TSVB.xcframework already exists, skipping extraction"
    fi
  CMD
end