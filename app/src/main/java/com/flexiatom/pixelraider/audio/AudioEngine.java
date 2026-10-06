/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.flexiatom.pixelraider.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.flexiatom.pixelraider.game.AudioCues;
import com.flexiatom.pixelraider.game.Game;
import com.flexiatom.pixelraider.plat.KeyValue;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 播放面（规格 §七 :290-:295 在 P2 路线下的兑现）。
 *
 * <p><b>P2 ＝ 波形在启动期由代码生成、写成 wav 落 app 私有目录、一次性交给两个 {@link SoundPool}</b>
 * （用户 2026-10-05 逐字「好，P2就是最符合当初设计想法的，就P2」）。于是规格 §七 :292 的
 * {@code res/raw/*.ogg} 字面作废、<b>实质保住</b>：启动期一次装载，游戏进行中绝不读盘——
 * 装载之后磁盘上那些文件就不再被运行时碰了。
 *
 * <p>三条边界，都是别人踩过的形状：
 * <ul>
 *   <li><b>混音交给系统</b>：并发、解码、抢占、单声部音量全在 {@code SoundPool} 侧，本类每帧只做一个
 *       int 自减（配额）。上游那 6 格语音池与求和循环<b>不抄</b>——那是 §七 :290 让系统做的那件事。</li>
 *   <li><b>生成在后台线程</b>：26 只音效 + 3 条 BGM 的合成本机实算 ≈110～135 ms，塞进首帧就是白屏一下。
 *       生成完才 {@code load}，之前 {@link #play} 静默返回（不炸、也不假装响过）。
 *       低端 ARM 上那一格<b>未实测</b>；同版本目录里已有完整文件时整段跳过，二次启动不付这笔。</li>
 *   <li><b>任何 IO 失败就地转静音</b>（上游 mod.rs 的降级纪律）：写盘失败只意味着没声音，
 *       不允许把异常抛进渲染线程——那会把"音频没配上"升级成"游戏崩了"。</li>
 * </ul>
 */
public final class AudioEngine implements Game.Audio {

    /**
     * 配方版本键。改 {@link CueBank}/{@link MusicBank}/{@link ChipWave} 的任何音色参数都要 +1，
     * 否则老设备读到的还是上一次生成的 wav（生成结果按版本目录缓存）。
     */
    private static final int GEN_VERSION = 1;

    /** 规格 §六 :280「每帧音效配额 10」。夹的是**每帧新增次数**，与 {@code setMaxStreams} 的并发流数是两件事。 */
    private static final int PER_FRAME_QUOTA = 10;

    /** 玩家总线并发上限。比配额宽，是因为上一帧的余音要能在这一帧继续响，配额只管新增。 */
    private static final int PLAYER_STREAMS = 12;
    /** 世界总线并发上限：爆炸声比枪声稀，但一次清屏会同时来好几团。 */
    private static final int WORLD_STREAMS = 6;

    /** 规格 §七 :291 的 -6dB 已经烘进样本，这里只留一个焦点闪避的乘子。 */
    private static final float DUCK_GAIN = 0.3f;

    private static final String KEY_MUTED = "audio_muted";
    private static final String KEY_VOLUME = "audio_volume_percent";

    private final Context ctx;
    private final KeyValue store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor gen = Executors.newSingleThreadExecutor();
    private final File dir;
    private final int[] soundIds = new int[AudioCues.COUNT];
    /** 只喂 {@code Log.i} 的读数（见 {@link #install()} 的注释）；回调在主线程，无需同步。 */
    private int loadedCount;
    private long loadStartedAt;

    private SoundPool playerPool;
    private SoundPool worldPool;
    private MediaPlayer bgm;
    private volatile boolean ready;
    private volatile boolean muted;
    private volatile float volume = 1f;
    private volatile float duck = 1f;
    private volatile int bgmTrack = -1;
    /** 帧配额只在渲染线程读写；UI 线程偶尔叫一声撞上自减，最坏结果是这一帧多放行一声，不是错账。 */
    private int frameQuota = PER_FRAME_QUOTA;

    public AudioEngine(Context ctx, KeyValue store) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        this.dir = new File(this.ctx.getFilesDir(), "audio/v" + GEN_VERSION);
        this.muted = store.getLong(KEY_MUTED, 0L) != 0L;
        int percent = (int) Math.max(0L, Math.min(100L, store.getLong(KEY_VOLUME, 100L)));
        this.volume = percent / 100f;
    }

    /**
     * 启动期一次性生成 + 装载。必须在主线程调（{@code SoundPool} 要 Looper 装它的回调）。
     *
     * <p>里面那几条 {@code Log.i} 不是调试残渣，是 P2 唯一的读数出口：这一格错掉的现场表现是**全程无声**
     * （{@code load()} 返回 0、{@code play()} 静默 return），屏幕上量不出任何东西。
     *
     * <p>读数口径：{@code 新建 n/29} 里的 n 才是"合成 + 写盘"那一遍，缓存命中的一只不求值波形。
     * 所以 warm 启动该看到 {@code 新建 0/29} 且 ms 接近 0（只剩 29 次 stat 加一遍目录），
     * 要是 warm 也花上秒级，就是有人在 {@code isCached} 之前把 {@code render} 求了出来。
     */
    public void install() {
        gen.execute(() -> {
            long t0 = System.nanoTime();
            try {
                if (!dir.isDirectory()) dir.mkdirs();
                int total = AudioCues.COUNT + MusicBank.TRACK_COUNT;
                int cached = 0;
                for (int cue = 0; cue < AudioCues.COUNT; cue++) {
                    String name = AudioCues.fileNameOf(cue);
                    if (isCached(name)) {
                        cached++;
                    } else {
                        writeWav(name, CueBank.render(cue));
                    }
                }
                for (int track = 0; track < MusicBank.TRACK_COUNT; track++) {
                    String name = AudioCues.bgmFileNameOf(track);
                    if (isCached(name)) {
                        cached++;
                    } else {
                        writeWav(name, MusicBank.render(track));
                    }
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                android.util.Log.i("PixelRaider", "audio gen " + ms + "ms 新建 " + (total - cached) + "/" + total
                        + " 个, " + dirBytes() + " B");
                main.post(this::loadAll);
            } catch (Exception e) {
                android.util.Log.w("PixelRaider", "audio generation failed, staying silent", e);
            }
        });
    }

    private boolean isCached(String name) {
        File f = new File(dir, name + ".wav");
        return f.isFile() && f.length() > 44L;
    }

    private void writeWav(String name, float[] wave) throws java.io.IOException {
        WavSink.writeMonoS16(new File(dir, name + ".wav"), wave);
    }

    private long dirBytes() {
        File[] fs = dir.listFiles();
        long sum = 0;
        if (fs != null) {
            for (File f : fs) sum += f.length();
        }
        return sum;
    }

    private void loadAll() {
        if (ready) return;
        playerPool = newPool(PLAYER_STREAMS);
        worldPool = newPool(WORLD_STREAMS);
        // 解码完成是异步的：26 条全到齐才打一行，否则一次装机刷 26 行日志
        SoundPool.OnLoadCompleteListener onLoaded = (pool, sampleId, status) -> {
            if (++loadedCount == AudioCues.COUNT) {
                android.util.Log.i("PixelRaider", "sfx decoded " + loadedCount + "/" + AudioCues.COUNT
                        + " in " + (System.nanoTime() - loadStartedAt) / 1_000_000L + "ms");
            }
        };
        playerPool.setOnLoadCompleteListener(onLoaded);
        worldPool.setOnLoadCompleteListener(onLoaded);
        loadStartedAt = System.nanoTime();
        int refused = 0;
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            SoundPool pool = AudioCues.busOf(cue) == AudioCues.BUS_WORLD ? worldPool : playerPool;
            soundIds[cue] = pool.load(new File(dir, AudioCues.fileNameOf(cue) + ".wav").getAbsolutePath(),
                    AudioCues.priorityOf(cue));
            if (soundIds[cue] == 0) refused++;
        }
        if (refused > 0) {
            android.util.Log.w("PixelRaider", refused + " 只 wav 被 SoundPool 当场拒收（load 返回 0）");
        }
        bgm = new MediaPlayer();
        bgm.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        bgm.setLooping(true);
        bgm.setOnErrorListener((mp, what, extra) -> {
            android.util.Log.w("PixelRaider", "BGM 出错 what=" + what + " extra=" + extra);
            return true;
        });
        ready = true;
        // 这里**不**替游戏挑轨：装载完可能已经过了好几帧，硬写 BGM_MENU 会把游戏实际所在的那条轨盖掉。
        // 轨号只有一个真源——Game 每帧推的那一次（未 ready 期间的推送在 setBgm 里被丢掉，不记账）。
    }

    /**
     * {@code SoundPool.Builder} 与旧构造函数的分支：本仓已有同形先例（{@code MainActivity} 的
     * {@code AudioFocusRequest} 那一族按 {@link Build.VERSION_CODES#O} 分岔），照它的形状走。
     */
    private static SoundPool newPool(int maxStreams) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new SoundPool.Builder()
                    .setMaxStreams(maxStreams)
                    .setAudioAttributes(attrs)
                    .build();
        }
        return new SoundPool(maxStreams, AudioManager.STREAM_MUSIC, 0);
    }

    @Override
    public void beginFrame() {
        frameQuota = PER_FRAME_QUOTA;
    }

    @Override
    public void play(int cue) {
        if (!ready || muted || cue < 0 || cue >= AudioCues.COUNT) return;
        if (frameQuota <= 0) return;
        frameQuota--;
        SoundPool pool = AudioCues.busOf(cue) == AudioCues.BUS_WORLD ? worldPool : playerPool;
        int id = soundIds[cue];
        if (id == 0) return;
        float v = volume * duck;
        pool.play(id, v, v, AudioCues.priorityOf(cue), 0, 1f);
    }

    @Override
    public void setBgm(int track) {
        // 未 ready 时**不记账**：这一格真发生过——装载要 1.2 s，期间游戏已经在推轨号；先记下 MENU
        // 再让装载完成后的第一次同轨推送走"同轨早退"，BGM 就永远不 started，全程无声且无提示。
        if (!ready || track == bgmTrack) return;
        bgmTrack = track;
        main.post(() -> startBgm(track));
    }

    /** 换轨＝重置指针从头（规格 §七 :294 作废后剩下的那半句实质：无交叉淡入、单播放面）。 */
    private void startBgm(int track) {
        if (!ready || bgm == null || track < 0) return;
        try {
            bgm.reset();
            bgm.setDataSource(new File(dir, AudioCues.bgmFileNameOf(track) + ".wav").getAbsolutePath());
            bgm.setLooping(true);
            bgm.setVolume(bgmGain(), bgmGain());
            bgm.setOnPreparedListener(MediaPlayer::start);
            bgm.prepareAsync();
        } catch (Exception e) {
            android.util.Log.w("PixelRaider", "bgm track " + track + " unavailable", e);
        }
    }

    private float bgmGain() {
        return muted ? 0f : volume * duck;
    }

    /** 规格 §七 :295：{@code onPause} 收总线与 BGM，{@code onResume} 反向。不靠 Activity 销毁去停音。 */
    public void pauseAll() {
        if (!ready) return;
        playerPool.autoPause();
        worldPool.autoPause();
        main.post(() -> {
            MediaPlayer mp = bgm;
            if (mp != null && mp.isPlaying()) mp.pause();
        });
    }

    public void resumeAll() {
        if (!ready) return;
        playerPool.autoResume();
        worldPool.autoResume();
        main.post(() -> {
            MediaPlayer mp = bgm;
            if (mp != null && !mp.isPlaying()) mp.start();
        });
    }

    /** 焦点闪避（{@code MainActivity} 那条 focusListener 的落点）。 */
    public void setDucked(boolean ducked) {
        duck = ducked ? DUCK_GAIN : 1f;
        main.post(this::applyBgmGain);
    }

    /**
     * 静音生效点。规格 §四 :193/:195 那两行的宿主是设置页，而设置页还没落地
     * （{@code ui/PauseScreen.java:61 SETTINGS_LIVE = false}）⇒ 他裁"音频侧先自建生效点"
     * （2026-10-05 第四轮）：布尔与乘子归本类持有并就地生效，控件等设置页来接。
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
        store.putLong(KEY_MUTED, muted ? 1L : 0L);
        store.flush();
        main.post(this::applyBgmGain);
    }

    /** @param percent 0～100，落盘用 long（{@link KeyValue} 只存 long）。 */
    public void setVolumePercent(int percent) {
        volume = Math.max(0, Math.min(100, percent)) / 100f;
        store.putLong(KEY_VOLUME, (long) Math.round(volume * 100));
        store.flush();
        main.post(this::applyBgmGain);
    }

    /**
     * 把当前的静音/音量/闪避乘子推到 BGM 上。三个调用点共用这一条，且**必须**就地吞掉异常：
     * {@code MediaPlayer} 在未初始化/已释放状态上收到 setVolume 会抛，那不该升级成一次崩溃。
     */
    private void applyBgmGain() {
        try {
            MediaPlayer mp = bgm;
            if (mp != null) mp.setVolume(bgmGain(), bgmGain());
        } catch (Exception e) {
            android.util.Log.w("PixelRaider", "bgm gain update skipped", e);
        }
    }

    public boolean isMuted() {
        return muted;
    }

    public int volumePercent() {
        return Math.round(volume * 100);
    }

    public void shutdown() {
        ready = false;
        SoundPool p = playerPool;
        SoundPool w = worldPool;
        playerPool = null;
        worldPool = null;
        MediaPlayer mp = bgm;
        bgm = null;
        main.post(() -> {
            if (p != null) p.release();
            if (w != null) w.release();
            if (mp != null) mp.release();
        });
    }
}
