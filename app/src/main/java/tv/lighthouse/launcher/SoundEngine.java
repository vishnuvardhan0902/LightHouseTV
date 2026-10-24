package tv.lighthouse.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * One very quiet click, played identically for "previous" and "next".
 * Nothing else: no drone, no bells.
 */
public class SoundEngine {

    private static final int SR = 44100;

    private final SharedPreferences prefs;
    private boolean enabled;
    private AudioTrack tick;

    public SoundEngine(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("lighthouse_tv", Context.MODE_PRIVATE);
        enabled = prefs.getBoolean("sound", true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggle() {
        enabled = !enabled;
        prefs.edit().putBoolean("sound", enabled).apply();
        return enabled;
    }

    // Kept for the activity's lifecycle; there is no continuous sound to start or stop.
    public void startDrone() {
    }

    public void stopDrone() {
    }

    /** The same soft tick for every step, whichever way the wheel turns. */
    public void step(int unused) {
        tick();
    }

    public void chime() {
        tick();
    }

    private void tick() {
        if (!enabled) return;
        if (tick == null) {
            short[] buf = tickBuffer();
            tick = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buf.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            tick.write(buf, 0, buf.length);
        }
        try {
            tick.stop();
            tick.setPlaybackHeadPosition(0);
            tick.setVolume(0.5f);
            tick.play();
        } catch (Exception ignored) {
        }
    }

    /** 55 ms: a soft wooden tick with a dull attack and no ring. */
    private short[] tickBuffer() {
        int n = (int) (SR * 0.055f);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float env = (float) Math.exp(-t * 90f) * Math.min(1f, t * 900f);
            // 620 Hz body plus a touch of 2nd harmonic, so it reads as wood, not a sine beep
            double s = Math.sin(2 * Math.PI * 620 * t) * 0.85
                    + Math.sin(2 * Math.PI * 1240 * t) * 0.15;
            out[i] = (short) (s * env * 0.20f * Short.MAX_VALUE * 0.35f);
        }
        return out;
    }
}
