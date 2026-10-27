package com.toolbox.adbhelper.server;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
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
 *
 * 全面支持 Android 11 (API 30) 至 Android 14+
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi"})
public class AudioForwarder {

    private static final String SOCKET_NAME = "scrcpy_audio";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int CHANNELS = 2;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    public static final String PACKAGE_NAME = "com.android.shell";

    private static Class<?> activityThreadClass;
    private static Object activityThreadInstance;
    private static FakeContext fakeContext;

    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        String mode = (args != null && args.length > 0) ? args[0].toLowerCase() : "auto";
        System.out.println("[AudioForwarder] Initializing: mode=" + mode + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");

        // 自动清理系统此前可能残留的堆转储广播与弹窗请求，防止堆积
        try {
            Runtime.getRuntime().exec(new String[]{"am", "broadcast", "-a", "com.android.shell.action.DELETE_HEAP_DUMP"});
        } catch (Throwable ignored) {}

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

            recorder = createAudioRecord(mode);
            if (recorder == null) {
                System.err.println("ERROR: Failed to create AudioRecord on device for mode=" + mode);
                return;
            }

            // 启动录音会话（针对 Android 11 前台伪装与状态检测）
            startRecordingWithWorkarounds(recorder);

            System.out.println("AUDIO_RECORDING_STARTED");
            System.out.flush();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufferSize = (minBuf > 0) ? Math.max(minBuf * 2, 8192) : 16384;
            byte[] buffer = new byte[bufferSize];
            long totalBytesSent = 0;
            long lastLogTime = System.currentTimeMillis();

            boolean isMic = "mic".equalsIgnoreCase(mode) || "voicerecog".equalsIgnoreCase(mode);
            int silenceThreshold = isMic ? 200 : 40;
            long lastSoundTime = System.currentTimeMillis();
            boolean inSilencePause = false;
            final long SILENCE_DELAY_MS = 1500L; // 连续无声音 1.5 秒后进入暂停传输状态，杜绝空跑流量

            while (!Thread.currentThread().isInterrupted()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    int maxAmp = getMaxAmplitude(buffer, 0, read);
                    long now = System.currentTimeMillis();

                    if (maxAmp > silenceThreshold) {
                        lastSoundTime = now;
                        if (inSilencePause) {
                            inSilencePause = false;
                            System.out.println("[AudioForwarder] Sound detected (peak=" + maxAmp + "), resuming audio transmission");
                            System.out.flush();
                        }
                        os.write(buffer, 0, read);
                        os.flush();
                        totalBytesSent += read;
                    } else {
                        // 当前 buffer 为静音
                        if (now - lastSoundTime < SILENCE_DELAY_MS) {
                            // 1.5 秒自然缓冲期内，继续传输尾音保证平滑过渡
                            os.write(buffer, 0, read);
                            os.flush();
                            totalBytesSent += read;
                        } else {
                            // 超过 1.5 秒无声，停止向网络 Socket 写入数据，网络传输量立刻降为 0
                            if (!inSilencePause) {
                                inSilencePause = true;
                                System.out.println("[AudioForwarder] Silence detected (>1.5s), pausing transmission to save bandwidth");
                                System.out.flush();
                            }
                        }
                    }

                    if (now - lastLogTime >= 3000) {
                        if (!inSilencePause) {
                            System.out.println("[AudioForwarder] Stream active: total " + (totalBytesSent / 1024) + " KB sent");
                        } else {
                            System.out.println("[AudioForwarder] Stream idle (silence): 0 B/s transferred");
                        }
                        System.out.flush();
                        lastLogTime = now;
                    }
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

    /**
     * 计算 16-bit PCM 字节流在指定范围内的最大绝对振幅
     */
    private static int getMaxAmplitude(byte[] buf, int offset, int len) {
        int max = 0;
        int end = offset + len - 1;
        for (int i = offset; i < end; i += 2) {
            int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            int abs = Math.abs(sample);
            if (abs > max) {
                max = abs;
            }
        }
        return max;
    }

    /**
     * 根据请求模式创建 AudioRecord
     */
    private static AudioRecord createAudioRecord(String mode) {
        AudioRecord record = null;

        // 模式 1: 指定麦克风源 (MIC)
        if ("mic".equalsIgnoreCase(mode)) {
            System.out.println("[AudioForwarder] User requested explicit MIC capture");
            return createMicRecord();
        }

        // 模式 2: 指定语音识别源
        if ("voicerecog".equalsIgnoreCase(mode)) {
            System.out.println("[AudioForwarder] User requested VOICE_RECOGNITION capture");
            return createVoiceRecognitionRecord();
        }

        // 模式 3: 默认自动模式 (系统声音内录优先，降级到 MIC)

        // 3.1 Android 13+ (API 33+) 优先使用 AudioPolicy 系统音频回环
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                record = createAudioPolicyRecord();
                if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                    System.out.println("[AudioForwarder] Selected strategy: AudioPolicy Loopback (Android 13+)");
                    return record;
                }
            } catch (Throwable t) {
                System.err.println("[AudioForwarder] AudioPolicy failed: " + t.getMessage());
            }
        }

        // 3.2 尝试 AudioRecord.Builder (REMOTE_SUBMIX 系统内录)
        try {
            record = createBuilderAudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected strategy: AudioRecord.Builder REMOTE_SUBMIX");
                return record;
            }
            if (record != null) { record.release(); record = null; }
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] Builder REMOTE_SUBMIX failed: " + t.getMessage());
        }

        // 3.3 尝试底层反射 native_setup 构造 REMOTE_SUBMIX
        try {
            record = createNativeAudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected strategy: native_setup REMOTE_SUBMIX");
                return record;
            }
            if (record != null) { record.release(); record = null; }
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] native_setup REMOTE_SUBMIX failed: " + t.getMessage());
        }

        // 3.4 尝试标准构造 REMOTE_SUBMIX
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufSize = (minBuf > 0) ? Math.max(minBuf * 4, 8192) : 16384;
            record = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX,
                    SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, bufSize);
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected strategy: Standard REMOTE_SUBMIX");
                return record;
            }
            record.release();
            record = null;
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] Standard REMOTE_SUBMIX failed: " + t.getMessage());
        }

        // 3.5 自动降级：尝试 MIC (麦克风)
        System.out.println("[AudioForwarder] REMOTE_SUBMIX unavailable on this system, auto-falling back to MIC");
        record = createMicRecord();
        if (record != null) {
            return record;
        }

        // 3.6 自动降级：尝试 VOICE_RECOGNITION
        return createVoiceRecognitionRecord();
    }

    private static AudioRecord createMicRecord() {
        try {
            AudioRecord record = createBuilderAudioRecord(MediaRecorder.AudioSource.MIC);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected fallback: Builder MIC");
                return record;
            }
            if (record != null) record.release();
        } catch (Throwable ignored) {}

        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufSize = (minBuf > 0) ? Math.max(minBuf * 4, 8192) : 16384;
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, bufSize);
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected fallback: Standard MIC");
                return record;
            }
            record.release();
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] MIC creation failed: " + t.getMessage());
        }
        return null;
    }

    private static AudioRecord createVoiceRecognitionRecord() {
        try {
            AudioRecord record = createBuilderAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected fallback: VOICE_RECOGNITION");
                return record;
            }
            if (record != null) record.release();
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 使用 AudioRecord.Builder 创建实例 (Android 6.0+)
     */
    private static AudioRecord createBuilderAudioRecord(int audioSource) {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= 31 && fakeContext != null) {
            builder.setContext(fakeContext);
        }
        builder.setAudioSource(audioSource);
        builder.setAudioFormat(new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build());

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        if (minBufferSize > 0) {
            builder.setBufferSizeInBytes(Math.max(minBufferSize * 8, 8192));
        }

        return builder.build();
    }

    /**
     * 启动录音：直接启动并校验录制状态，严禁拉起 HeapDumpActivity 导致界面弹窗
     */
    private static void startRecordingWithWorkarounds(AudioRecord recorder) throws Exception {
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new RuntimeException("Recorder failed to start, state: " + recorder.getRecordingState());
        }
    }

    /**
     * Android 13+ (API 33+) AudioPolicy 系统音频策略回环
     */
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
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION
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
        Context context = fakeContext != null ? fakeContext : getSystemContext();
        Object audioPolicyBuilder = audioPolicyBuilderClass.getConstructor(Context.class).newInstance(context);

        Method addMixMethod = audioPolicyBuilderClass.getMethod("addMix", audioMixClass);
        addMixMethod.invoke(audioPolicyBuilder, audioMix);

        Object audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder);

        Method registerAudioPolicyStaticMethod = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", audioPolicyClass);
        registerAudioPolicyStaticMethod.setAccessible(true);
        int regResult = (int) registerAudioPolicyStaticMethod.invoke(null, audioPolicy);
        if (regResult != 0) {
            throw new RuntimeException("registerAudioPolicyStatic returned error code: " + regResult);
        }

        Method createAudioRecordSinkClass = audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass);
        return (AudioRecord) createAudioRecordSinkClass.invoke(audioPolicy, audioMix);
    }

    /**
     * 底层反射 native_setup 构造 AudioRecord (精确设置 mAudioFormat)
     */
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
        mChannelCountField.set(audioRecord, CHANNELS);

        Field mChannelMaskField = AudioRecord.class.getDeclaredField("mChannelMask");
        mChannelMaskField.setAccessible(true);
        mChannelMaskField.set(audioRecord, CHANNEL_CONFIG);

        // 显式反射设置 mAudioFormat，防止其为 0 (ENCODING_INVALID)
        try {
            Field mAudioFormatField = AudioRecord.class.getDeclaredField("mAudioFormat");
            mAudioFormatField.setAccessible(true);
            mAudioFormatField.set(audioRecord, ENCODING);
        } catch (Throwable ignored) {}

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        int bufferSizeInBytes = (minBufferSize > 0) ? minBufferSize * 8 : 16384;

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
                    CHANNEL_CONFIG, 0, ENCODING, bufferSizeInBytes, session, PACKAGE_NAME, 0L);
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
                            sampleRateArray, CHANNEL_CONFIG, 0, ENCODING, bufferSizeInBytes, session,
                            parcel, 0L, 0);
                } else {
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                            int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class, int.class);
                    nativeSetupMethod.setAccessible(true);
                    initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<>(audioRecord), attributes,
                            sampleRateArray, CHANNEL_CONFIG, 0, ENCODING, bufferSizeInBytes, session,
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

    /**
     * 针对 app_process 环境注入 ActivityThread 假体及上下文
     */
    private static void applyWorkarounds() {
        try {
            activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            activityThreadInstance = constructor.newInstance();

            Field sCurrentField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentField.setAccessible(true);
            sCurrentField.set(null, activityThreadInstance);

            Field mSystemThreadField = activityThreadClass.getDeclaredField("mSystemThread");
            mSystemThreadField.setAccessible(true);
            mSystemThreadField.setBoolean(activityThreadInstance, true);

            // 填充 AppBindData
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
            mBoundApplicationField.set(activityThreadInstance, appBindData);

            fakeContext = new FakeContext(getSystemContext());

            // 填充 mInitialApplication
            try {
                Application app = Instrumentation.newApplication(Application.class, fakeContext);
                Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
                mInitialApplicationField.setAccessible(true);
                mInitialApplicationField.set(activityThreadInstance, app);
            } catch (Throwable ignored) {}

            // Android 12+ 配置控制器填充
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    Class<?> configCtrlClass = Class.forName("android.app.ConfigurationController");
                    Class<?> actThreadInternalClass = Class.forName("android.app.ActivityThreadInternal");
                    Constructor<?> configCtrlConstructor = configCtrlClass.getDeclaredConstructor(actThreadInternalClass);
                    configCtrlConstructor.setAccessible(true);
                    Object configCtrl = configCtrlConstructor.newInstance(activityThreadInstance);

                    Field mConfigurationControllerField = activityThreadClass.getDeclaredField("mConfigurationController");
                    mConfigurationControllerField.setAccessible(true);
                    mConfigurationControllerField.set(activityThreadInstance, configCtrl);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] applyWorkarounds failed: " + t.getMessage());
        }
    }

    private static Context getSystemContext() {
        try {
            if (activityThreadClass == null) {
                activityThreadClass = Class.forName("android.app.ActivityThread");
            }
            Method getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContextMethod.setAccessible(true);
            return (Context) getSystemContextMethod.invoke(activityThreadInstance);
        } catch (Throwable t) {
            return null;
        }
    }

    @TargetApi(31)
    private static AttributionSource getAttributionSource() {
        AttributionSource.Builder builder = new AttributionSource.Builder(Process.SHELL_UID);
        builder.setPackageName(PACKAGE_NAME);
        return builder.build();
    }

    /**
     * 模拟 ContextWrapper 提供合法的 shell 包名和上下文
     */
    public static final class FakeContext extends ContextWrapper {
        public FakeContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @TargetApi(31)
        @Override
        public AttributionSource getAttributionSource() {
            return AudioForwarder.getAttributionSource();
        }
    }
}
