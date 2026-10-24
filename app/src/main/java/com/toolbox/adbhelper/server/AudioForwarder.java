package com.toolbox.adbhelper.server;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
    private static final String PACKAGE_NAME = "com.android.shell";

    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        System.out.println("[AudioForwarder] Initializing workarounds...");
        applyWorkarounds();

        LocalServerSocket serverSocket = null;
        LocalSocket clientSocket = null;
        AudioRecord recorder = null;

        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            System.out.println("AUDIO_READY");
            System.out.flush();

            clientSocket = serverSocket.accept();
            OutputStream os = clientSocket.getOutputStream();

            recorder = createAudioRecord();
            if (recorder == null) {
                System.err.println("ERROR: Failed to create AudioRecord");
                return;
            }

            recorder.startRecording();
            System.out.println("AUDIO_RECORDING_STARTED");
            System.out.flush();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufferSize = Math.max(minBuf, 4096);
            byte[] buffer = new byte[bufferSize];

            while (!Thread.currentThread().isInterrupted()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    os.write(buffer, 0, read);
                } else if (read < 0) {
                    System.err.println("AudioRecord read error: " + read);
                    break;
                }
            }
        } catch (Throwable t) {
            System.err.println("AudioForwarder exception: " + t.getMessage());
            t.printStackTrace();
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

        // 1. 优先尝试 Android 13+ (API 33+) AudioPolicy 系统音频策略回环录制
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                record = createAudioPolicyRecord();
                if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                    System.out.println("[AudioForwarder] Using AudioPolicy playback capture");
                    return record;
                }
            } catch (Throwable t) {
                System.err.println("[AudioForwarder] AudioPolicy failed: " + t.getMessage());
            }
        }

        // Android 11 特殊前台保活
        if (Build.VERSION.SDK_INT == 30) {
            startWorkaroundAndroid11();
        }

        // 2. 尝试底层反射 native_setup 构建 REMOTE_SUBMIX
        try {
            record = createNativeAudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Using native_setup REMOTE_SUBMIX");
                return record;
            }
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] native_setup failed: " + t.getMessage());
        }

        // 3. 尝试直接标准构造 REMOTE_SUBMIX
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            record = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX,
                    SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, Math.max(minBuf * 4, 8192));
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Using standard REMOTE_SUBMIX");
                return record;
            }
            record.release();
            record = null;
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] standard REMOTE_SUBMIX failed: " + t.getMessage());
        }

        // 4. 降级尝试 MIC
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, Math.max(minBuf * 4, 8192));
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Using fallback MIC");
                return record;
            }
            record.release();
            record = null;
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] fallback MIC failed: " + t.getMessage());
        }

        return null;
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
        Context context = getFakeContext();
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

    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private static AudioRecord createNativeAudioRecord(int source) throws Exception {
        Constructor<AudioRecord> audioRecordConstructor = AudioRecord.class.getDeclaredConstructor(long.class);
        audioRecordConstructor.setAccessible(true);
        AudioRecord audioRecord = audioRecordConstructor.newInstance(0L);

        Field mRecordingStateField = AudioRecord.class.getDeclaredField("mRecordingState");
        mRecordingStateField.setAccessible(true);
        mRecordingStateField.set(audioRecord, AudioRecord.RECORDSTATE_STOPPED);

        Looper looper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
        Field mInitializationLooperField = AudioRecord.class.getDeclaredField("mInitializationLooper");
        mInitializationLooperField.setAccessible(true);
        mInitializationLooperField.set(audioRecord, looper);

        AudioAttributes.Builder audioAttributesBuilder = new AudioAttributes.Builder();
        Method setInternalCapturePresetMethod = AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class);
        setInternalCapturePresetMethod.invoke(audioAttributesBuilder, source);
        AudioAttributes attributes = audioAttributesBuilder.build();

        Field mAudioAttributesField = AudioRecord.class.getDeclaredField("mAudioAttributes");
        mAudioAttributesField.setAccessible(true);
        mAudioAttributesField.set(audioRecord, attributes);

        Method audioParamCheckMethod = AudioRecord.class.getDeclaredMethod("audioParamCheck", int.class, int.class, int.class);
        audioParamCheckMethod.setAccessible(true);
        audioParamCheckMethod.invoke(audioRecord, source, SAMPLE_RATE, ENCODING);

        Field mChannelCountField = AudioRecord.class.getDeclaredField("mChannelCount");
        mChannelCountField.setAccessible(true);
        mChannelCountField.set(audioRecord, 2);

        Field mChannelMaskField = AudioRecord.class.getDeclaredField("mChannelMask");
        mChannelMaskField.setAccessible(true);
        mChannelMaskField.set(audioRecord, CHANNEL_CONFIG);

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        int bufferSizeInBytes = minBufferSize * 4;

        Method audioBuffSizeCheckMethod = AudioRecord.class.getDeclaredMethod("audioBuffSizeCheck", int.class);
        audioBuffSizeCheckMethod.setAccessible(true);
        audioBuffSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes);

        int[] sampleRateArray = new int[]{SAMPLE_RATE};
        int[] session = new int[]{AudioManager.AUDIO_SESSION_ID_GENERATE};

        int initResult;
        if (Build.VERSION.SDK_INT < 31) {
            Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, int.class,
                    int.class, int.class, int.class, int[].class, String.class, long.class);
            nativeSetupMethod.setAccessible(true);
            initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<>(audioRecord), attributes, sampleRateArray,
                    CHANNEL_CONFIG, 0, audioRecord.getAudioFormat(), bufferSizeInBytes, session, PACKAGE_NAME, 0L);
        } else {
            AttributionSource attributionSource = getAttributionSource();
            Method asScopedParcelStateMethod = AttributionSource.class.getDeclaredMethod("asScopedParcelState");
            asScopedParcelStateMethod.setAccessible(true);

            try (AutoCloseable state = (AutoCloseable) asScopedParcelStateMethod.invoke(attributionSource)) {
                Method getParcelMethod = state.getClass().getDeclaredMethod("getParcel");
                Parcel parcel = (Parcel) getParcelMethod.invoke(state);

                if (Build.VERSION.SDK_INT < 34) {
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                            int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class);
                    nativeSetupMethod.setAccessible(true);
                    initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<>(audioRecord), attributes,
                            sampleRateArray, CHANNEL_CONFIG, 0, audioRecord.getAudioFormat(), bufferSizeInBytes, session,
                            parcel, 0L, 0);
                } else {
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                            int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class, int.class);
                    nativeSetupMethod.setAccessible(true);
                    initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<>(audioRecord), attributes,
                            sampleRateArray, CHANNEL_CONFIG, 0, audioRecord.getAudioFormat(), bufferSizeInBytes, session,
                            parcel, 0L, 0, 0);
                }
            }
        }

        if (initResult != AudioRecord.SUCCESS) {
            throw new RuntimeException("native_setup failed with code " + initResult);
        }

        Field mSampleRateField = AudioRecord.class.getDeclaredField("mSampleRate");
        mSampleRateField.setAccessible(true);
        mSampleRateField.set(audioRecord, sampleRateArray[0]);

        Field mSessionIdField = AudioRecord.class.getDeclaredField("mSessionId");
        mSessionIdField.setAccessible(true);
        mSessionIdField.set(audioRecord, session[0]);

        Field mStateField = AudioRecord.class.getDeclaredField("mState");
        mStateField.setAccessible(true);
        mStateField.set(audioRecord, AudioRecord.STATE_INITIALIZED);

        return audioRecord;
    }

    private static void applyWorkarounds() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object activityThread = constructor.newInstance();

            Field sCurrentField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentField.setAccessible(true);
            sCurrentField.set(null, activityThread);

            Field mSystemThreadField = activityThreadClass.getDeclaredField("mSystemThread");
            mSystemThreadField.setAccessible(true);
            mSystemThreadField.setBoolean(activityThread, true);

            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
            appBindDataConstructor.setAccessible(true);
            Object appBindData = appBindDataConstructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = PACKAGE_NAME;

            Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(appBindData, applicationInfo);

            Field mBoundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication");
            mBoundApplicationField.setAccessible(true);
            mBoundApplicationField.set(activityThread, appBindData);
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] Workarounds.apply failed: " + t.getMessage());
        }
    }

    private static void startWorkaroundAndroid11() {
        try {
            Context ctx = getSystemContext();
            if (ctx != null) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(PACKAGE_NAME, "com.android.shell.HeapDumpActivity"));
                ctx.startActivity(intent);
            }
        } catch (Throwable ignored) {}
    }

    private static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread");
            Object thread = currentActivityThreadMethod.invoke(null);
            if (thread == null) {
                Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                thread = constructor.newInstance();
            }
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            return (Context) getSystemContext.invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context getFakeContext() {
        Context sys = getSystemContext();
        if (sys == null) return null;
        return new ContextWrapper(sys) {
            @Override
            public String getPackageName() {
                return PACKAGE_NAME;
            }

            @Override
            public String getOpPackageName() {
                return PACKAGE_NAME;
            }

            @TargetApi(31)
            @Override
            public AttributionSource getAttributionSource() {
                return AudioForwarder.getAttributionSource();
            }
        };
    }

    @TargetApi(31)
    private static AttributionSource getAttributionSource() {
        AttributionSource.Builder builder = new AttributionSource.Builder(Process.SHELL_UID);
        builder.setPackageName(PACKAGE_NAME);
        return builder.build();
    }
}
