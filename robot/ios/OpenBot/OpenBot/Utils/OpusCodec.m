//
//  OpusCodec.m
//  OpenBot
//
//  libopus wrapper for the Xiaozhi voice channel (Voice Control v3). The C API
//  (opus_encoder_create / opus_encode / opus_decoder_create / opus_decode) is kept
//  out of Swift's way; Swift talks to OpusCodecEncoder / OpusCodecDecoder only.
//

#import "OpusCodec.h"
#import <libopus/opus.h>

@implementation AVAudioPlayerNode (OpenBotSafe)

- (BOOL)openbot_safePlay {
    @try {
        [self play];
        return YES;
    } @catch (NSException *exception) {
        NSLog(@"AVAudioPlayerNode play refused: %@", exception.reason);
        return NO;
    }
}

@end

/// Largest opus packet for one frame (RFC 6716), with slack for multistream.
static const int kMaxPacketBytes = 4000;
/// Longest packet the decoder accepts: 120 ms (the opus maximum).
static const int kMaxFrameMs = 120;

@implementation OpusCodecEncoder {
    OpusEncoder *_encoder;
    int _channels;
    int _frameSize;
}

- (nullable instancetype)initWithSampleRate:(int)sampleRate
                                   channels:(int)channels
                                  frameSize:(int)frameSize {
    self = [super init];
    if (self == nil) {
        return nil;
    }
    int error = OPUS_OK;
    _encoder = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &error);
    if (_encoder == NULL || error != OPUS_OK) {
        return nil;
    }
    _channels = channels;
    _frameSize = frameSize;
    return self;
}

- (void)dealloc {
    if (_encoder != NULL) {
        opus_encoder_destroy(_encoder);
        _encoder = NULL;
    }
}

- (nullable NSData *)encodePCM16:(NSData *)pcm {
    if (_encoder == NULL) {
        return nil;
    }
    // Exactly one frame of interleaved Int16 samples per packet.
    NSUInteger expected = (NSUInteger)_frameSize * (NSUInteger)_channels * sizeof(opus_int16);
    if (pcm.length != expected) {
        return nil;
    }
    NSMutableData *out = [NSMutableData dataWithLength:(NSUInteger)kMaxPacketBytes];
    int written = opus_encode(_encoder,
                              (const opus_int16 *)pcm.bytes,
                              _frameSize,
                              (unsigned char *)out.mutableBytes,
                              kMaxPacketBytes);
    if (written <= 0) {
        return nil;
    }
    out.length = (NSUInteger)written;
    return out;
}

@end

@implementation OpusCodecDecoder {
    OpusDecoder *_decoder;
    int _sampleRate;
    int _channels;
}

- (nullable instancetype)initWithSampleRate:(int)sampleRate channels:(int)channels {
    self = [super init];
    if (self == nil) {
        return nil;
    }
    int error = OPUS_OK;
    _decoder = opus_decoder_create(sampleRate, channels, &error);
    if (_decoder == NULL || error != OPUS_OK) {
        return nil;
    }
    _sampleRate = sampleRate;
    _channels = channels;
    return self;
}

- (void)dealloc {
    if (_decoder != NULL) {
        opus_decoder_destroy(_decoder);
        _decoder = NULL;
    }
}

- (nullable NSData *)decodePacket:(NSData *)packet {
    if (_decoder == NULL || packet.length == 0 || packet.length > UINT16_MAX) {
        return nil;
    }
    // One packet decodes to at most 120 ms of audio.
    int maxSamples = _sampleRate * kMaxFrameMs / 1000;
    NSMutableData *out = [NSMutableData dataWithLength:(NSUInteger)maxSamples * _channels * sizeof(opus_int16)];
    int decoded = opus_decode(_decoder,
                              (const unsigned char *)packet.bytes,
                              (opus_int32)packet.length,
                              (opus_int16 *)out.mutableBytes,
                              maxSamples,
                              0);
    if (decoded <= 0) {
        return nil;
    }
    out.length = (NSUInteger)decoded * (NSUInteger)_channels * sizeof(opus_int16);
    return out;
}

@end
