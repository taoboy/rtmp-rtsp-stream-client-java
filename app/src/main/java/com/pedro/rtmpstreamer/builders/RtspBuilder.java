package com.pedro.rtmpstreamer.builders;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.os.Build;
import android.util.Base64;
import android.view.SurfaceView;
import com.pedro.encoder.audio.AudioEncoder;
import com.pedro.encoder.audio.GetAccData;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.input.audio.MicrophoneManager;
import com.pedro.encoder.input.video.Camera1ApiManager;
import com.pedro.encoder.input.video.EffectManager;
import com.pedro.encoder.input.video.GetCameraData;
import com.pedro.encoder.video.FormatVideoEncoder;
import com.pedro.encoder.video.GetH264Data;
import com.pedro.encoder.video.VideoEncoder;
import com.pedro.rtsp.rtp.packets.AccPacket;
import com.pedro.rtsp.rtp.packets.H264Packet;
import com.pedro.rtsp.rtsp.Protocol;
import com.pedro.rtsp.rtsp.RtspClient;
import com.pedro.rtsp.utils.ConnectCheckerRtsp;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by pedro on 10/02/17.
 */

public class RtspBuilder implements GetAccData, GetCameraData, GetH264Data, GetMicrophoneData {

  private Camera1ApiManager cameraManager;
  private VideoEncoder videoEncoder;
  private MicrophoneManager microphoneManager;
  private AudioEncoder audioEncoder;
  private boolean streaming;

  private RtspClient rtspClient;
  private AccPacket accPacket;
  private H264Packet h264Packet;
  private boolean videoEnabled = true;

  public RtspBuilder(SurfaceView surfaceView, Protocol protocol,
      ConnectCheckerRtsp connectCheckerRtsp) {
    rtspClient = new RtspClient(connectCheckerRtsp, protocol);
    accPacket = new AccPacket(rtspClient, protocol);
    h264Packet = new H264Packet(rtspClient, protocol);

    cameraManager = new Camera1ApiManager(surfaceView, this);
    videoEncoder = new VideoEncoder(this);
    microphoneManager = new MicrophoneManager(this);
    audioEncoder = new AudioEncoder(this);
    streaming = false;
  }

  public void setAuthorization(String user, String password) {
    rtspClient.setAuthorization(user, password);
  }

  public boolean prepareVideo(int width, int height, int fps, int bitrate, int rotation) {
    cameraManager.prepareCamera(width, height, fps, ImageFormat.NV21);
    return videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation,
        FormatVideoEncoder.YUV420Dynamical);
  }

  public boolean prepareAudio(int bitrate, int sampleRate, boolean isStereo) {
    rtspClient.setSampleRate(sampleRate);
    accPacket.setSampleRate(sampleRate);
    microphoneManager.createMicrophone(sampleRate, isStereo);
    return audioEncoder.prepareAudioEncoder(bitrate, sampleRate, isStereo);
  }

  public boolean prepareVideo() {
    cameraManager.prepareCamera();
    return videoEncoder.prepareVideoEncoder();
  }

  public boolean prepareAudio() {
    microphoneManager.createMicrophone();
    rtspClient.setSampleRate(microphoneManager.getSampleRate());
    accPacket.setSampleRate(microphoneManager.getSampleRate());
    return audioEncoder.prepareAudioEncoder();
  }

  public void startStream(String url) {
    videoEncoder.start();
    audioEncoder.start();
    cameraManager.start();
    microphoneManager.start();
    rtspClient.setUrl(url);
    streaming = true;
  }

  public void stopStream() {
    rtspClient.disconnect();
    cameraManager.stop();
    microphoneManager.stop();
    videoEncoder.stop();
    audioEncoder.stop();
    streaming = false;
    accPacket.close();
    h264Packet.close();
  }

  public List<String> getResolutions() {
    List<Camera.Size> list = cameraManager.getPreviewSize();
    List<String> resolutions = new ArrayList<>();
    for (Camera.Size size : list) {
      resolutions.add(size.width + "X" + size.height);
    }
    return resolutions;
  }

  public void disableAudio() {
    microphoneManager.mute();
  }

  public void enableAudio() {
    microphoneManager.unMute();
  }

  public void disableVideo() {
    videoEncoder.startSendBlackImage();
    videoEnabled = false;
  }

  public void enableVideo() {
    videoEncoder.stopSendBlackImage();
    videoEnabled = true;
  }

  public boolean isAudioMuted() {
    return microphoneManager.isMuted();
  }

  public boolean isVideoEnabled() {
    return videoEnabled;
  }

  public void switchCamera() {
    if (isStreaming()) {
      cameraManager.switchCamera();
    }
  }

  /** need min API 19 */
  public void setVideoBitrateOnFly(int bitrate) {
    if (Build.VERSION.SDK_INT >= 19) {
      videoEncoder.setVideoBitrateOnFly(bitrate);
    }
  }

  public boolean isStreaming() {
    return streaming;
  }

  public void setEffect(EffectManager effect) {
    if (isStreaming()) {
      cameraManager.setEffect(effect);
    }
  }

  @Override
  public void getAccData(ByteBuffer accBuffer, MediaCodec.BufferInfo info) {
    accPacket.createAndSendPacket(accBuffer, info);
  }

  @Override
  public void onSPSandPPS(ByteBuffer sps, ByteBuffer pps) {
    byte[] mSPS = new byte[sps.capacity() - 4];
    sps.position(4);
    sps.get(mSPS, 0, mSPS.length);
    byte[] mPPS = new byte[pps.capacity() - 4];
    pps.position(4);
    pps.get(mPPS, 0, mPPS.length);

    String sSPS = Base64.encodeToString(mSPS, 0, mSPS.length, Base64.NO_WRAP);
    String sPPS = Base64.encodeToString(mPPS, 0, mPPS.length, Base64.NO_WRAP);
    rtspClient.setSPSandPPS(sSPS, sPPS);
    rtspClient.connect();
  }

  @Override
  public void getH264Data(ByteBuffer h264Buffer, MediaCodec.BufferInfo info) {
    h264Packet.createAndSendPacket(h264Buffer, info);
  }

  @Override
  public void inputPcmData(byte[] buffer, int size) {
    audioEncoder.inputPcmData(buffer, size);
  }

  @Override
  public void inputYv12Data(byte[] buffer, int width, int height) {
    videoEncoder.inputYv12Data(buffer, width, height);
  }

  @Override
  public void inputNv21Data(byte[] buffer, int width, int height) {
    videoEncoder.inputNv21Data(buffer, width, height);
  }

  public void updateDestination() {
    accPacket.updateDestinationAudio();
    h264Packet.updateDestinationVideo();
  }
}
