package net.ossrs.rtmp;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.github.faucamp.simplertmp.DefaultRtmpPublisher;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by winlin on 5/2/15.
 * Updated by leoma on 4/1/16.
 * modified by pedro
 * to POST the h.264/avc annexb frame over RTMP.
 *
 * Usage:
 * muxer = new SrsRtmp("rtmp://ossrs.net/live/yasea");
 * muxer.start();
 *
 * MediaFormat aformat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, asample_rate,
 * achannel);
 * // setup the aformat for audio.
 * atrack = muxer.addTrack(aformat);
 *
 * MediaFormat vformat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vsize.width,
 * vsize.height);
 * // setup the vformat for video.
 * vtrack = muxer.addTrack(vformat);
 *
 * // encode the video frame from camera by h.264 codec to es and bi,
 * // where es is the h.264 ES(element stream).
 * ByteBuffer es, MediaCodec.BufferInfo bi;
 * muxer.writeSampleData(vtrack, es, bi);
 *
 * // encode the audio frame from microphone by aac codec to es and bi,
 * // where es is the aac ES(element stream).
 * ByteBuffer es, MediaCodec.BufferInfo bi;
 * muxer.writeSampleData(atrack, es, bi);
 *
 * muxer.stop();
 * muxer.release();
 */
public class SrsFlvMuxer {


  private volatile boolean connected = false;
  private DefaultRtmpPublisher publisher;

  private Thread worker;
  private final Object txFrameLock = new Object();

  private SrsFlv flv = new SrsFlv();
  private boolean needToFindKeyFrame = true;
  private SrsFlvFrame mVideoSequenceHeader;
  private SrsFlvFrame mAudioSequenceHeader;
  private SrsAllocator mVideoAllocator = new SrsAllocator(128 * 1024);
  private SrsAllocator mAudioAllocator = new SrsAllocator(4 * 1024);
  private ConcurrentLinkedQueue<SrsFlvFrame> mFlvTagCache = new ConcurrentLinkedQueue<>();

  private static final String TAG = "SrsFlvMuxer";

  /**
   * constructor.
   */
  public SrsFlvMuxer() {
    publisher = new DefaultRtmpPublisher();
  }

  public void setJksData(InputStream inputStreamJks, String passPhraseJks){
    publisher.setJksData(inputStreamJks, passPhraseJks);
  }

  /**
   * get cached video frame number in publisher
   */
  public AtomicInteger getVideoFrameCacheNumber() {
    return publisher == null ? null : publisher.getVideoFrameCacheNumber();
  }

  /**
   * set video resolution for publisher
   *
   * @param width width
   * @param height height
   */
  public void setVideoResolution(int width, int height) {
    if (publisher != null) {
      publisher.setVideoResolution(width, height);
    }
  }

  private void disconnect(ConnectCheckerRtmp connectChecker) {
    try {
      publisher.close();
    } catch (IllegalStateException e) {
      // Ignore illegal state.
    }
    connected = false;
    mVideoSequenceHeader = null;
    mAudioSequenceHeader = null;
    connectChecker.onDisconnectRtmp();
    Log.i(TAG, "worker: disconnect ok.");
  }

  private boolean connect(String url) {
    if (!connected) {
      Log.i(TAG, String.format("worker: connecting to RTMP server by url=%s\n", url));
      if (publisher.connect(url)) {
        connected = publisher.publish("live");
      }
      mVideoSequenceHeader = null;
      mAudioSequenceHeader = null;
    }
    return connected;
  }

  private void sendFlvTag(SrsFlvFrame frame) {
    if (!connected || frame == null) {
      return;
    }

    if (frame.is_video()) {
      if (frame.is_keyframe()) {
        Log.i(TAG,
            String.format("worker: send frame type=%d, dts=%d, size=%dB", frame.type, frame.dts,
                frame.flvTag.array().length));
      }
      publisher.publishVideoData(frame.flvTag.array(), frame.flvTag.size(), frame.dts);
      mVideoAllocator.release(frame.flvTag);
    } else if (frame.is_audio()) {
      publisher.publishAudioData(frame.flvTag.array(), frame.flvTag.size(), frame.dts);
      mAudioAllocator.release(frame.flvTag);
    }
  }

  /**
   * start to the remote SRS for remux.
   */
  public void start(final String rtmpUrl, final ConnectCheckerRtmp connectChecker) {
    worker = new Thread(new Runnable() {
      @Override
      public void run() {
        if (!connect(rtmpUrl)) {
          connectChecker.onConnectionFailedRtmp();
          return;
        }
        connectChecker.onConnectionSuccessRtmp();
        while (!Thread.interrupted()) {
          while (!mFlvTagCache.isEmpty()) {
            SrsFlvFrame frame = mFlvTagCache.poll();
            if (frame.is_sequenceHeader()) {
              if (frame.is_video()) {
                mVideoSequenceHeader = frame;
                sendFlvTag(mVideoSequenceHeader);
              } else if (frame.is_audio()) {
                mAudioSequenceHeader = frame;
                sendFlvTag(mAudioSequenceHeader);
              }
            } else {
              if (frame.is_video() && mVideoSequenceHeader != null) {
                sendFlvTag(frame);
              } else if (frame.is_audio() && mAudioSequenceHeader != null) {
                sendFlvTag(frame);
              }
            }
          }
          // Waiting for next frame
          synchronized (txFrameLock) {
            try {
              // isEmpty() may take some time, so we set timeout to detect next frame
              txFrameLock.wait(500);
            } catch (InterruptedException ie) {
              worker.interrupt();
            }
          }
        }
      }
    });
    worker.start();
  }

  /**
   * stop the muxer, disconnect RTMP connection.
   */
  public void stop(final ConnectCheckerRtmp connectChecker) {
    mFlvTagCache.clear();
    if (worker != null) {
      worker.interrupt();
      try {
        worker.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
        worker.interrupt();
      }
      worker = null;
    }
    flv.reset();
    needToFindKeyFrame = true;
    Log.i(TAG, "SrsFlvMuxer closed");

    new Thread(new Runnable() {
      @Override
      public void run() {
        disconnect(connectChecker);
      }
    }).start();
  }

  public void sendVideo(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
    flv.writeVideoSample(byteBuffer, bufferInfo);
  }

  public void sendAudio(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
    flv.writeAudioSample(byteBuffer, bufferInfo);
  }

  // E.4.3.1 VIDEODATA
  // Frame Type UB [4]
  // Type of video frame. The following values are defined:
  //     1 = key frame (for AVC, a seekable frame)
  //     2 = inter frame (for AVC, a non-seekable frame)
  //     3 = disposable inter frame (H.263 only)
  //     4 = generated key frame (reserved for server use only)
  //     5 = video info/command frame
  private class SrsCodecVideoAVCFrame {
    public final static int KeyFrame = 1;
    public final static int InterFrame = 2;
  }

  // AVCPacketType IF CodecID == 7 UI8
  // The following values are defined:
  //     0 = AVC sequence header
  //     1 = AVC NALU
  //     2 = AVC end of sequence (lower level NALU sequence ender is
  //         not required or supported)
  private class SrsCodecVideoAVCType {
    public final static int SequenceHeader = 0;
    public final static int NALU = 1;
  }

  /**
   * E.4.1 FLV Tag, page 75
   */
  private class SrsCodecFlvTag {
    // 8 = audio
    public final static int Audio = 8;
    // 9 = video
    public final static int Video = 9;
  }

  ;

  // E.4.3.1 VIDEODATA
  // CodecID UB [4]
  // Codec Identifier. The following values are defined:
  //     2 = Sorenson H.263
  //     3 = Screen video
  //     4 = On2 VP6
  //     5 = On2 VP6 with alpha channel
  //     6 = Screen video version 2
  //     7 = AVC
  private class SrsCodecVideo {
    public final static int AVC = 7;
  }

  /**
   * the aac object type, for RTMP sequence header
   * for AudioSpecificConfig, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 33
   * for audioObjectType, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 23
   */
  private class SrsAacObjectType {
    public final static int AacLC = 2;
  }

  /**
   * Table 7-1 – NAL unit type codes, syntax element categories, and NAL unit type classes
   * H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 83.
   */
  private class SrsAvcNaluType {
    // Coded slice of an IDR picture slice_layer_without_partitioning_rbsp( )
    public final static int IDR = 5;
    // Sequence parameter set seq_parameter_set_rbsp( )
    public final static int SPS = 7;
    // Picture parameter set pic_parameter_set_rbsp( )
    public final static int PPS = 8;
    // Access unit delimiter access_unit_delimiter_rbsp( )
    public final static int AccessUnitDelimiter = 9;
  }

  /**
   * utils functions.
   */
  public class SrsUtils {
    private SrsAnnexbSearch as = new SrsAnnexbSearch();

    public SrsAnnexbSearch avc_startswith_annexb(ByteBuffer bb, MediaCodec.BufferInfo bi) {
      as.match = false;
      as.nb_start_code = 0;

      for (int i = bb.position(); i < bi.size - 3; i++) {
        // not match.
        if (bb.get(i) != 0x00 || bb.get(i + 1) != 0x00) {
          break;
        }

        // match N[00] 00 00 01, where N>=0
        if (bb.get(i + 2) == 0x01) {
          as.match = true;
          as.nb_start_code = i + 3 - bb.position();
          break;
        }
      }

      return as;
    }
  }

  /**
   * the search result for annexb.
   */
  private class SrsAnnexbSearch {
    public int nb_start_code = 0;
    public boolean match = false;
  }

  /**
   * the demuxed tag frame.
   */
  private class SrsFlvFrameBytes {
    public ByteBuffer data;
    public int size;
  }

  /**
   * the muxed flv frame.
   */
  private class SrsFlvFrame {
    // the tag bytes.
    public SrsAllocator.Allocation flvTag;
    // the codec type for audio/aac and video/avc for instance.
    public int avc_aac_type;
    // the frame type, keyframe or not.
    public int frame_type;
    // the tag type, audio, video or data.
    public int type;
    // the dts in ms, tbn is 1000.
    public int dts;

    public boolean is_keyframe() {
      return is_video() && frame_type == SrsCodecVideoAVCFrame.KeyFrame;
    }

    public boolean is_sequenceHeader() {
      return avc_aac_type == 0;
    }

    public boolean is_video() {
      return type == SrsCodecFlvTag.Video;
    }

    public boolean is_audio() {
      return type == SrsCodecFlvTag.Audio;
    }
  }

  /**
   * the raw h.264 stream, in annexb.
   */
  private class SrsRawH264Stream {
    private final static String TAG = "SrsFlvMuxer";

    private SrsUtils utils = new SrsUtils();
    private SrsFlvFrameBytes nalu_header = new SrsFlvFrameBytes();
    private SrsFlvFrameBytes seq_hdr = new SrsFlvFrameBytes();
    private SrsFlvFrameBytes sps_hdr = new SrsFlvFrameBytes();
    private SrsFlvFrameBytes sps_bb = new SrsFlvFrameBytes();
    private SrsFlvFrameBytes pps_hdr = new SrsFlvFrameBytes();
    private SrsFlvFrameBytes pps_bb = new SrsFlvFrameBytes();

    public boolean is_sps(SrsFlvFrameBytes frame) {
      return frame.size >= 1 && (frame.data.get(0) & 0x1f) == SrsAvcNaluType.SPS;
    }

    public boolean is_pps(SrsFlvFrameBytes frame) {
      return frame.size >= 1 && (frame.data.get(0) & 0x1f) == SrsAvcNaluType.PPS;
    }

    public SrsFlvFrameBytes mux_nalu_header(SrsFlvFrameBytes frame) {
      if (nalu_header.data == null) {
        nalu_header.data = ByteBuffer.allocate(4);
        nalu_header.size = 4;
      }
      nalu_header.data.rewind();

      // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
      // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size
      int NAL_unit_length = frame.size;

      // mux the avc NALU in "ISO Base Media File Format"
      // from H.264-AVC-ISO_IEC_14496-15.pdf, page 20
      // NALUnitLength
      nalu_header.data.putInt(NAL_unit_length);

      // reset the buffer.
      nalu_header.data.rewind();
      return nalu_header;
    }

    public void mux_sequence_header(ByteBuffer sps, ByteBuffer pps, int dts, int pts,
        ArrayList<SrsFlvFrameBytes> frames) {
      // 5bytes sps/pps header:
      //      configurationVersion, AVCProfileIndication, profile_compatibility,
      //      AVCLevelIndication, lengthSizeMinusOne
      // 3bytes size of sps:
      //      numOfSequenceParameterSets, sequenceParameterSetLength(2B)
      // Nbytes of sps.
      //      sequenceParameterSetNALUnit
      // 3bytes size of pps:
      //      numOfPictureParameterSets, pictureParameterSetLength
      // Nbytes of pps:
      //      pictureParameterSetNALUnit

      // decode the SPS:
      // @see: 7.3.2.1.1, H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 62
      if (seq_hdr.data == null) {
        seq_hdr.data = ByteBuffer.allocate(5);
        seq_hdr.size = 5;
      }
      seq_hdr.data.rewind();
      // @see: Annex A Profiles and levels, H.264-AVC-ISO_IEC_14496-10.pdf, page 205
      //      Baseline profile profile_idc is 66(0x42).
      //      Main profile profile_idc is 77(0x4d).
      //      Extended profile profile_idc is 88(0x58).
      byte profile_idc = sps.get(1);
      //u_int8_t constraint_set = frame[2];
      byte level_idc = sps.get(3);

      // generate the sps/pps header
      // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
      // configurationVersion
      seq_hdr.data.put((byte) 0x01);
      // AVCProfileIndication
      seq_hdr.data.put(profile_idc);
      // profile_compatibility
      seq_hdr.data.put((byte) 0x00);
      // AVCLevelIndication
      seq_hdr.data.put(level_idc);
      // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size,
      // so we always set it to 0x03.
      seq_hdr.data.put((byte) 0x03);

      // reset the buffer.
      seq_hdr.data.rewind();
      frames.add(seq_hdr);

      // sps
      if (sps_hdr.data == null) {
        sps_hdr.data = ByteBuffer.allocate(3);
        sps_hdr.size = 3;
      }
      sps_hdr.data.rewind();
      // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
      // numOfSequenceParameterSets, always 1
      sps_hdr.data.put((byte) 0x01);
      // sequenceParameterSetLength
      sps_hdr.data.putShort((short) sps.array().length);

      sps_hdr.data.rewind();
      frames.add(sps_hdr);

      // sequenceParameterSetNALUnit
      sps_bb.size = sps.array().length;
      sps_bb.data = sps.duplicate();
      frames.add(sps_bb);

      // pps
      if (pps_hdr.data == null) {
        pps_hdr.data = ByteBuffer.allocate(3);
        pps_hdr.size = 3;
      }
      pps_hdr.data.rewind();
      // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
      // numOfPictureParameterSets, always 1
      pps_hdr.data.put((byte) 0x01);
      // pictureParameterSetLength
      pps_hdr.data.putShort((short) pps.array().length);

      pps_hdr.data.rewind();
      frames.add(pps_hdr);

      // pictureParameterSetNALUnit
      pps_bb.size = pps.array().length;
      pps_bb.data = pps.duplicate();
      frames.add(pps_bb);
    }

    public SrsAllocator.Allocation mux_avc2flv(ArrayList<SrsFlvFrameBytes> frames, int frame_type,
        int avc_packet_type, int dts, int pts) {
      // for h264 in RTMP video payload, there is 5bytes header:
      //      1bytes, FrameType | CodecID
      //      1bytes, AVCPacketType
      //      3bytes, CompositionTime, the cts.
      // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
      SrsAllocator.Allocation allocation = mVideoAllocator.allocate();

      // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
      // Frame Type, Type of video frame.
      // CodecID, Codec Identifier.
      // set the rtmp header
      allocation.put((byte) ((frame_type << 4) | SrsCodecVideo.AVC));

      // AVCPacketType
      allocation.put((byte) avc_packet_type);

      // CompositionTime
      // pts = dts + cts, or
      // cts = pts - dts.
      // where cts is the header in rtmp video packet payload header.
      int cts = pts - dts;
      allocation.put((byte) (cts >> 16));
      allocation.put((byte) (cts >> 8));
      allocation.put((byte) cts);

      // h.264 raw data.
      for (int i = 0; i < frames.size(); i++) {
        SrsFlvFrameBytes frame = frames.get(i);
        frame.data.get(allocation.array(), allocation.size(), frame.size);
        allocation.appendOffset(frame.size);
      }

      return allocation;
    }

    public SrsFlvFrameBytes annexb_demux(ByteBuffer bb, MediaCodec.BufferInfo bi) {
      SrsFlvFrameBytes tbb = new SrsFlvFrameBytes();

      while (bb.position() < bi.size) {
        // each frame must prefixed by annexb format.
        // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
        SrsAnnexbSearch tbbsc = utils.avc_startswith_annexb(bb, bi);
        if (!tbbsc.match || tbbsc.nb_start_code < 3) {
          Log.e(TAG, "annexb not match.");
        }

        // the start codes.
        for (int i = 0; i < tbbsc.nb_start_code; i++) {
          bb.get();
        }

        // find out the frame size.
        tbb.data = bb.slice();
        int pos = bb.position();
        while (bb.position() < bi.size) {
          SrsAnnexbSearch bsc = utils.avc_startswith_annexb(bb, bi);
          if (bsc.match) {
            break;
          }
          bb.get();
        }

        tbb.size = bb.position() - pos;
        break;
      }

      return tbb;
    }
  }

  /**
   * remux the annexb to flv tags.
   */
  private class SrsFlv {
    private SrsRawH264Stream avc = new SrsRawH264Stream();
    private ArrayList<SrsFlvFrameBytes> ipbs = new ArrayList<>();
    private SrsAllocator.Allocation audio_tag;
    private SrsAllocator.Allocation video_tag;
    private ByteBuffer h264_sps;
    private boolean h264_sps_changed;
    private ByteBuffer h264_pps;
    private boolean h264_pps_changed;
    private boolean h264_sps_pps_sent;
    private boolean aac_specific_config_got;

    public SrsFlv() {
      reset();
    }

    public void reset() {
      h264_sps_changed = false;
      h264_pps_changed = false;
      h264_sps_pps_sent = false;
      aac_specific_config_got = false;
    }

    public void writeAudioSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) {
      int pts = (int) (bi.presentationTimeUs / 1000);
      int dts = pts;

      audio_tag = mAudioAllocator.allocate();
      byte aac_packet_type = 1; // 1 = AAC raw
      if (!aac_specific_config_got) {
        // @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf
        // AudioSpecificConfig (), page 33
        // 1.6.2.1 AudioSpecificConfig
        // audioObjectType; 5 bslbf
        byte ch = (byte) (bb.get(0) & 0xf8);
        // 3bits left.

        // samplingFrequencyIndex; 4 bslbf
        byte samplingFrequencyIndex = 0x04;
        ch |= (samplingFrequencyIndex >> 1) & 0x07;
        audio_tag.put(ch, 2);

        ch = (byte) ((samplingFrequencyIndex << 7) & 0x80);
        // 7bits left.

        // channelConfiguration; 4 bslbf
        byte channelConfiguration = 1;

        ch |= (channelConfiguration << 3) & 0x78;
        // 3bits left.

        // GASpecificConfig(), page 451
        // 4.4.1 Decoder configuration (GASpecificConfig)
        // frameLengthFlag; 1 bslbf
        // dependsOnCoreCoder; 1 bslbf
        // extensionFlag; 1 bslbf
        audio_tag.put(ch, 3);

        aac_specific_config_got = true;
        aac_packet_type = 0; // 0 = AAC sequence header

        write_adts_header(audio_tag.array(), 4);
        audio_tag.appendOffset(7);
      } else {
        bb.get(audio_tag.array(), 2, bi.size);
        audio_tag.appendOffset(bi.size + 2);
      }

      byte sound_format = 10; // AAC
      byte sound_type = 0; // 0 = Mono sound

      byte sound_size = 1; // 1 = 16-bit samples
      byte sound_rate = 3; // 44100, 22050, 11025

      // for audio frame, there is 1 or 2 bytes header:
      //      1bytes, SoundFormat|SoundRate|SoundSize|SoundType
      //      1bytes, AACPacketType for SoundFormat == 10, 0 is sequence header.
      byte audio_header = (byte) (sound_type & 0x01);
      audio_header |= (sound_size << 1) & 0x02;
      audio_header |= (sound_rate << 2) & 0x0c;
      audio_header |= (sound_format << 4) & 0xf0;

      audio_tag.put(audio_header, 0);
      audio_tag.put(aac_packet_type, 1);

      rtmp_write_packet(SrsCodecFlvTag.Audio, dts, 0, aac_packet_type, audio_tag);
    }

    private void write_adts_header(byte[] frame, int offset) {
      // adts sync word 0xfff (12-bit)
      frame[offset] = (byte) 0xff;
      frame[offset + 1] = (byte) 0xf0;
      // versioin 0 for MPEG-4, 1 for MPEG-2 (1-bit)
      frame[offset + 1] |= 0 << 3;
      // layer 0 (2-bit)
      frame[offset + 1] |= 0 << 1;
      // protection absent: 1 (1-bit)
      frame[offset + 1] |= 1;
      // profile: audio_object_type - 1 (2-bit)
      frame[offset + 2] = (SrsAacObjectType.AacLC - 1) << 6;
      // sampling frequency index: 4 (4-bit)
      frame[offset + 2] |= (4 & 0xf) << 2;
      // channel configuration (3-bit)
      frame[offset + 2] |= (2 & (byte) 0x4) >> 2;
      frame[offset + 3] = (byte) ((2 & (byte) 0x03) << 6);
      // original: 0 (1-bit)
      frame[offset + 3] |= 0 << 5;
      // home: 0 (1-bit)
      frame[offset + 3] |= 0 << 4;
      // copyright id bit: 0 (1-bit)
      frame[offset + 3] |= 0 << 3;
      // copyright id start: 0 (1-bit)
      frame[offset + 3] |= 0 << 2;
      // frame size (13-bit)
      frame[offset + 3] |= ((frame.length - 2) & 0x1800) >> 11;
      frame[offset + 4] = (byte) (((frame.length - 2) & 0x7f8) >> 3);
      frame[offset + 5] = (byte) (((frame.length - 2) & 0x7) << 5);
      // buffer fullness (0x7ff for variable bitrate)
      frame[offset + 5] |= (byte) 0x1f;
      frame[offset + 6] = (byte) 0xfc;
      // number of data block (nb - 1)
      frame[offset + 6] |= 0x0;
    }

    public void writeVideoSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) {
      int pts = (int) (bi.presentationTimeUs / 1000);
      int dts = pts;
      int frame_type = SrsCodecVideoAVCFrame.InterFrame;

      // send each frame.
      while (bb.position() < bi.size) {
        SrsFlvFrameBytes frame = avc.annexb_demux(bb, bi);

        // 5bits, 7.3.1 NAL unit syntax,
        // H.264-AVC-ISO_IEC_14496-10.pdf, page 44.
        // 7: SPS, 8: PPS, 5: I Frame, 1: P Frame
        int nal_unit_type = (int) (frame.data.get(0) & 0x1f);
        if (nal_unit_type == SrsAvcNaluType.SPS || nal_unit_type == SrsAvcNaluType.PPS) {
          Log.i(TAG, String.format("annexb demux %dB, pts=%d, frame=%dB, nalu=%d", bi.size, pts,
              frame.size, nal_unit_type));
        }

        // for IDR frame, the frame is keyframe.
        if (nal_unit_type == SrsAvcNaluType.IDR) {
          frame_type = SrsCodecVideoAVCFrame.KeyFrame;
        }

        // ignore the nalu type aud(9)
        if (nal_unit_type == SrsAvcNaluType.AccessUnitDelimiter) {
          continue;
        }

        // for sps
        if (avc.is_sps(frame)) {
          if (!frame.data.equals(h264_sps)) {
            byte[] sps = new byte[frame.size];
            frame.data.get(sps);
            h264_sps_changed = true;
            h264_sps = ByteBuffer.wrap(sps);
          }
          continue;
        }

        // for pps
        if (avc.is_pps(frame)) {
          if (!frame.data.equals(h264_pps)) {
            byte[] pps = new byte[frame.size];
            frame.data.get(pps);
            h264_pps_changed = true;
            h264_pps = ByteBuffer.wrap(pps);
          }
          continue;
        }

        // ibp frame.
        SrsFlvFrameBytes nalu_header = avc.mux_nalu_header(frame);
        ipbs.add(nalu_header);
        ipbs.add(frame);

        // Drop the es frames with huge size which are mostly generated at encoder stop
        // since the encoder may work unsteadily at that moment.
        if (nalu_header.size + frame.size <= mVideoAllocator.getIndividualAllocationSize()) {
          write_h264_sps_pps(dts, pts);
          write_h264_ipb_frame(ipbs, frame_type, dts, pts);
        }
        ipbs.clear();
      }
    }

    private void write_h264_sps_pps(int dts, int pts) {
      // when sps or pps changed, update the sequence header,
      // for the pps maybe not changed while sps changed.
      // so, we must check when each video ts message frame parsed.
      if (h264_sps_pps_sent && !h264_sps_changed && !h264_pps_changed) {
        return;
      }

      // when not got sps/pps, wait.
      if (h264_pps == null || h264_sps == null) {
        return;
      }

      // h264 raw to h264 packet.
      ArrayList<SrsFlvFrameBytes> frames = new ArrayList<>();
      avc.mux_sequence_header(h264_sps, h264_pps, dts, pts, frames);

      // h264 packet to flv packet.
      int frame_type = SrsCodecVideoAVCFrame.KeyFrame;
      int avc_packet_type = SrsCodecVideoAVCType.SequenceHeader;
      video_tag = avc.mux_avc2flv(frames, frame_type, avc_packet_type, dts, pts);

      // the timestamp in rtmp message header is dts.
      rtmp_write_packet(SrsCodecFlvTag.Video, dts, frame_type, avc_packet_type, video_tag);

      // reset sps and pps.
      h264_sps_changed = false;
      h264_pps_changed = false;
      h264_sps_pps_sent = true;
      Log.i(TAG, String.format("flv: h264 sps/pps sent, sps=%dB, pps=%dB", h264_sps.array().length,
          h264_pps.array().length));
    }

    private void write_h264_ipb_frame(ArrayList<SrsFlvFrameBytes> frames, int frame_type, int dts,
        int pts) {
      // when sps or pps not sent, ignore the packet.
      // @see https://github.com/simple-rtmp-server/srs/issues/203
      if (!h264_sps_pps_sent) {
        return;
      }

      int avc_packet_type = SrsCodecVideoAVCType.NALU;
      video_tag = avc.mux_avc2flv(frames, frame_type, avc_packet_type, dts, pts);

      // the timestamp in rtmp message header is dts.
      rtmp_write_packet(SrsCodecFlvTag.Video, dts, frame_type, avc_packet_type, video_tag);
    }

    private void rtmp_write_packet(int type, int dts, int frame_type, int avc_aac_type,
        SrsAllocator.Allocation tag) {
      SrsFlvFrame frame = new SrsFlvFrame();
      frame.flvTag = tag;
      frame.type = type;
      frame.dts = dts;
      frame.frame_type = frame_type;
      frame.avc_aac_type = avc_aac_type;

      if (frame.is_video()) {
        if (needToFindKeyFrame) {
          if (frame.is_keyframe()) {
            needToFindKeyFrame = false;
            flvFrameCacheAdd(frame);
          }
        } else {
          flvFrameCacheAdd(frame);
        }
      } else if (frame.is_audio()) {
        flvFrameCacheAdd(frame);
      }
    }

    private void flvFrameCacheAdd(SrsFlvFrame frame) {
      mFlvTagCache.add(frame);
      if (frame.is_video()) {
        getVideoFrameCacheNumber().incrementAndGet();
      }
      synchronized (txFrameLock) {
        txFrameLock.notifyAll();
      }
    }
  }
}