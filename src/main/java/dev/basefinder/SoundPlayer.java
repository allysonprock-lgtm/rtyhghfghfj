package dev.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.File;
import java.nio.file.Path;

/** Plays the user's custom WAV if present, otherwise a built-in Minecraft sound. */
final class SoundPlayer {
    private SoundPlayer() {}

    static void play(Minecraft mc, Config cfg) {
        File custom = resolve(cfg.customSoundFile);
        if (custom != null) {
            Thread t = new Thread(() -> playFile(custom, cfg.soundVolume), "basefinder-sound");
            t.setDaemon(true);
            t.start();
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f, cfg.soundVolume));
    }

    private static File resolve(String name) {
        if (name == null || name.isBlank()) return null;
        Path p = Path.of(name.trim());
        if (!p.isAbsolute()) p = Config.dir().resolve(p);
        File f = p.toFile();
        return f.isFile() ? f : null;
    }

    private static void playFile(File file, float volume) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (float) (20.0 * Math.log10(Math.max(0.0001, volume)));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
            clip.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) clip.close();
            });
            clip.start();
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not play custom sound {} (use an uncompressed .wav): {}",
                    file, e.toString());
        }
    }
}
