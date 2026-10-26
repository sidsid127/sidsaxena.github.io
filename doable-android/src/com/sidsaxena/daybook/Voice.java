package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.UUID;

/** Voice notes: record to an .m4a file in private storage, play back. */
final class Voice {
    private Voice() {}

    private static MediaPlayer player;
    private static String playing;

    interface Done {
        void onRecorded(String file);
    }

    @SuppressWarnings("deprecation")
    static void record(final MainActivity a, final Done done) {
        final String name = UUID.randomUUID() + ".m4a";
        final MediaRecorder rec = new MediaRecorder();
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioEncodingBitRate(64000);
            rec.setAudioSamplingRate(44100);
            rec.setOutputFile(Attachments.file(a, name).getPath());
            rec.prepare();
            rec.start();
        } catch (IOException | RuntimeException e) {
            rec.release();
            Toast.makeText(a, "Could not start recording", Toast.LENGTH_SHORT).show();
            return;
        }
        final long started = SystemClock.elapsedRealtime();
        final TextView timer = new TextView(a);
        timer.setTextSize(32);
        timer.setGravity(android.view.Gravity.CENTER);
        timer.setPadding(0, Util.dp(a, 24), 0, Util.dp(a, 8));
        timer.setTextColor(Util.color(a, R.color.text));
        final Runnable tick = new Runnable() {
            public void run() {
                long s = (SystemClock.elapsedRealtime() - started) / 1000;
                timer.setText("🔴  " + s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60));
                timer.postDelayed(this, 500);
            }
        };
        tick.run();
        final boolean[] finished = {false};
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Recording voice note")
                .setView(timer)
                .setPositiveButton("Stop & save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        finished[0] = true;
                        timer.removeCallbacks(tick);
                        try {
                            rec.stop();
                            done.onRecorded(name);
                        } catch (RuntimeException e) {
                            Attachments.file(a, name).delete(); // too short
                            Toast.makeText(a, "Recording too short", Toast.LENGTH_SHORT).show();
                        }
                        rec.release();
                    }
                })
                .setNegativeButton("Discard", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dlg) {
                if (finished[0]) return;
                timer.removeCallbacks(tick);
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
                Attachments.file(a, name).delete();
            }
        });
        d.setCanceledOnTouchOutside(false);
        d.show();
    }

    /** Plays a voice note; tapping the same one again stops it. */
    static void toggle(MainActivity a, String file) {
        boolean same = file.equals(playing);
        stop();
        if (same) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(Attachments.file(a, file).getPath());
            player.prepare();
            player.start();
            playing = file;
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { stop(); }
            });
            Toast.makeText(a, "Playing… tap again to stop", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            stop();
            Toast.makeText(a, "Could not play this recording", Toast.LENGTH_SHORT).show();
        }
    }

    static void stop() {
        if (player != null) {
            player.release();
            player = null;
        }
        playing = null;
    }

    static String duration(MainActivity a, String file) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(Attachments.file(a, file).getPath());
            mp.prepare();
            int s = mp.getDuration() / 1000;
            return s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
        } catch (IOException | RuntimeException e) {
            return "";
        } finally {
            mp.release();
        }
    }
}
