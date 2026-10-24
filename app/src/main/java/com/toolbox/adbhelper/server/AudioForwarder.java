package com.toolbox.adbhelper.server;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Looper;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 运行在对端 Android 设备的音频采集与转发服务端
 * 通过 app_process 启动，监听 localabstract:scrcpy_audio
 * 将系统的 PCM 原始音频流无延迟地输出到 LocalSocket
 */
public class AudioForwarder {

    private static final String SOCKET_NAME = "scrcpy_audio";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        LocalServerSocket serverSocket = null;
        LocalSocket clientSocket = null;
        AudioRecord recorder = null;

        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            System.out.println("AUDIO_SERVER_STARTED");
            System.out.flush();

            clientSocket = serverSocket.accept();
            OutputStream os = clientSocket.getOutputStream();

            recorder = createAudioRecord();
            if (recorder == null) {
                System.err.println("ERROR: Unable to initialize AudioRecord");
                return;
            }

            recorder.startRecording();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufferSize = Math.max(minBuf, 4096);
            byte[] buffer = new byte[bufferSize];

            while (!Thread.currentThread().isInterrupted()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    os.write(buffer, 0, read);
                } else if (read < 0) {
                    break;
                }
            }
        } catch (Throwable t) {
            System.err.println("Audio forwarder error: " + t.getMessage());
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                    recorder.release();
                } catch (Throwable ignored) {}
            }
            if (clientSocket != null) {
                try { clientSocket.close(); } catch (Throwable ignored) {}
            }
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static AudioRecord createAudioRecord() {
        AudioRecord record = null;

        // 1. 优先尝试 Android 13+ (API 33+) AudioPolicy 系统音频混音回环录制
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                record = createAudioPolicyRecord();
            } catch (Throwable ignored) {}
        }

        // 2. 尝试 REMOTE_SUBMIX (系统内录混音)
        if (record == null) {
            try {
                int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
                record = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX,
                        SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, Math.max(minBuf * 2, 8192));
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    record.release();
                    record = null;
                }
            } catch (Throwable ignored) {}
        }

        // 3. 兜底尝试 MIC (麦克风降级)
        if (record == null) {
            try {
                int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
                record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, Math.max(minBuf * 2, 8192));
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    record.release();
                    record = null;
                }
            } catch (Throwable ignored) {}
        }

        return record;
    }

    private static AudioRecord createAudioPolicyRecord() throws Exception {
        Class<?> audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> audioMixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object audioMixingRuleBuilder = audioMixingRuleBuilderClass.getConstructor().newInstance();

        int mixRolePlayersConstant = audioMixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
        Method setTargetMixRoleMethod = audioMixingRuleBuilderClass.getMethod("setTargetMixRole", int.class);
        setTargetMixRoleMethod.invoke(audioMixingRuleBuilder, mixRolePlayersConstant);

        int[] usages = {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN
        };

        int ruleMatchAttributeUsageConstant = audioMixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
        Method addMixRuleMethod = audioMixingRuleBuilderClass.getMethod("addMixRule", int.class, Object.class);

        for (int usage : usages) {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(usage).build();
            addMixRuleMethod.invoke(audioMixingRuleBuilder, ruleMatchAttributeUsageConstant, attributes);
        }

        Object audioMixingRule = audioMixingRuleBuilderClass.getMethod("build").invoke(audioMixingRuleBuilder);

        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object audioMixBuilder = audioMixBuilderClass.getConstructor(audioMixingRuleClass).newInstance(audioMixingRule);

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build();

        Method setFormat = audioMixBuilder.getClass().getMethod("setFormat", AudioFormat.class);
        setFormat.invoke(audioMixBuilder, audioFormat);

        int routeFlags = audioMixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);
        Method setRouteFlags = audioMixBuilder.getClass().getMethod("setRouteFlags", int.class);
        setRouteFlags.invoke(audioMixBuilder, routeFlags);

        Object audioMix = audioMixBuilderClass.getMethod("build").invoke(audioMixBuilder);

        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> audioPolicyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Context context = getSystemContext();
        Object audioPolicyBuilder = audioPolicyBuilderClass.getConstructor(Context.class).newInstance(context);

        Method addMixMethod = audioPolicyBuilderClass.getMethod("addMix", audioMixClass);
        addMixMethod.invoke(audioPolicyBuilder, audioMix);

        Object audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder);

        Method registerAudioPolicyStaticMethod = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", audioPolicyClass);
        registerAudioPolicyStaticMethod.setAccessible(true);
        registerAudioPolicyStaticMethod.invoke(null, audioPolicy);

        Method createAudioRecordSinkClass = audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass);
        return (AudioRecord) createAudioRecordSinkClass.invoke(audioPolicy, audioMix);
    }

    private static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object activityThread = constructor.newInstance();
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Throwable t) {
            return null;
        }
    }
}
