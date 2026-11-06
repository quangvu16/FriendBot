//
//  OpusCodec.h
//  OpenBot
//
//  Tiny Objective-C wrapper around libopus for the Xiaozhi voice channel
//  (Voice Control v3). The websocket carries RAW OPUS binary frames in both
//  directions (hello "version": 1, no framing header):
//    - uplink:   16 kHz mono PCM16 in 960-sample (60 ms) frames -> opus packets
//    - downlink: opus packets -> PCM16 at the server's playback sample rate
//

#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// play() is exception-throwing in AVFAudio (it raises NSException instead of
/// returning the NSError), which aborts a Swift app. Every play on this screen
/// goes through here so a bad engine state can never take the app down.
@interface AVAudioPlayerNode (OpenBotSafe)
- (BOOL)openbot_safePlay;
@end

/// Opus encoder for the microphone uplink: fixed-size PCM16 frames -> one packet each.
@interface OpusCodecEncoder : NSObject

- (nullable instancetype)initWithSampleRate:(int)sampleRate
                                   channels:(int)channels
                                  frameSize:(int)frameSize NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

/// Encodes exactly `frameSize * channels` Int16 samples (interleaved) into one raw
/// opus packet. Returns nil when the input has the wrong size or encoding fails.
- (nullable NSData *)encodePCM16:(NSData *)pcm;

@end

/// Opus decoder for the TTS downlink: one raw packet -> one block of Int16 PCM.
@interface OpusCodecDecoder : NSObject

- (nullable instancetype)initWithSampleRate:(int)sampleRate
                                   channels:(int)channels NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

/// Decodes one raw opus packet into interleaved Int16 PCM at the decoder's sample
/// rate. Returns nil on malformed input.
- (nullable NSData *)decodePacket:(NSData *)packet;

@end

NS_ASSUME_NONNULL_END
