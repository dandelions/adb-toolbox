package com.toolbox.adbhelper.server;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.ContentResolver;
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

        System.out.println("[AudioForwarder] Initializing for Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")...");
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
                System.err.println("ERROR: Failed to create AudioRecord on device");
                return;
            }

            // 启动录音会话（针对 Android 11 具备前台 activity 保活与重试机制）
            startRecordingWithWorkarounds(recorder);

            System.out.println("AUDIO_RECORDING_STARTED");
            System.out.flush();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufferSize = Math.max(minBuf * 2, 4096);
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

    /**
     * 创建 AudioRecord，按照优先级依次降级尝试
     */
    private static AudioRecord createAudioRecord() {
        AudioRecord record = null;

        // 1. Android 13+ (API 33+) 优先使用 AudioPolicy 静态回环策略
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

        // 2. Android 11+ (API 30+) 优先尝试 AudioRecord.Builder (REMOTE_SUBMIX 系统声音)
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

        // 3. 尝试反射 native_setup 构造 REMOTE_SUBMIX (绕过部分厂商 ROM 的 context 检查)
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

        // 4. 尝试直接标准构造 REMOTE_SUBMIX
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            record = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX,
                    SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, Math.max(minBuf * 4, 8192));
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected strategy: Standard REMOTE_SUBMIX");
                return record;
            }
            record.release();
            record = null;
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] Standard REMOTE_SUBMIX failed: " + t.getMessage());
        }

        // 5. 降级尝试 VOICE_RECOGNITION 音源
        try {
            record = createBuilderAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected fallback: VOICE_RECOGNITION");
                return record;
            }
            if (record != null) { record.release(); record = null; }
        } catch (Throwable ignored) {}

        // 6. 最终降级尝试 MIC (麦克风)
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, Math.max(minBuf * 4, 8192));
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[AudioForwarder] Selected fallback: MIC (microphone)");
                return record;
            }
            record.release();
            record = null;
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] Fallback MIC failed: " + t.getMessage());
        }

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
     * 在 Android 11 上启动录音：配合 Shell 前台 Activity 绕过系统后台录音限制
     */
    private static void startRecordingWithWorkarounds(AudioRecord recorder) throws Exception {
        if (Build.VERSION.SDK_INT == 30) {
            System.out.println("[AudioForwarder] Android 11 detected: applying foreground workaround...");
            startWorkaroundAndroid11();
            boolean success = false;
            Throwable lastErr = null;

            // 循环尝试启动录音，给前台 Activity 切换留出充足时间 (最多等待 6 次，共约 720ms)
            for (int i = 0; i < 6; i++) {
                try {
                    Thread.sleep(120);
                    recorder.startRecording();
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        success = true;
                        System.out.println("[AudioForwarder] Android 11 recording successfully started (attempt " + (i + 1) + ")");
                        break;
                    }
                } catch (Throwable t) {
                    lastErr = t;
                }
            }

            // 录音会话一旦底层建立成功，关闭前台 activity 不会影响流录制
            stopWorkaroundAndroid11();

            if (!success) {
                if (lastErr != null) {
                    throw new RuntimeException("Android 11 startRecording failed: " + lastErr.getMessage(), lastErr);
                } else {
                    throw new RuntimeException("Android 11 recorder state is not RECORDING (" + recorder.getRecordingState() + ")");
                }
            }
        } else {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new RuntimeException("Recorder failed to start, state: " + recorder.getRecordingState());
            }
        }
    }

    private static void startWorkaroundAndroid11() {
        try {
            // 通过 am 命令行以阻塞等待模式唤起 HeapDumpActivity，确保 UID 2000 置于前台
            Runtime.getRuntime().exec(new String[]{
                "am", "start", "-n", PACKAGE_NAME + "/.HeapDumpActivity"
            });
        } catch (Throwable t) {
            System.err.println("[AudioForwarder] startWorkaroundAndroid11 exec failed: " + t.getMessage());
        }
    }

    private static void stopWorkaroundAndroid11() {
        try {
            // 通过 am force-stop 关闭前台 Activity
            Runtime.getRuntime().exec(new String[]{
                "am", "force-stop", PACKAGE_NAME
            });
        } catch (Throwable ignored) {}
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

        // 关键：必须显式反射设置 mAudioFormat，防止其为 0 (ENCODING_INVALID)
        try {
            Field mAudioFormatField = AudioRecord.class.getDeclaredField("mAudioFormat");
            mAudioFormatField.setAccessible(true);
            mAudioFormatField.set(audioRecord, ENCODING);
        } catch (Throwable ignored) {}

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        int bufferSizeInBytes = minBufferSize * 8;

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
            // 关键：audioFormat 传参严格传入 ENCODING (2)，绝不能传未初始化的 0！
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

            // 填充 mInitialApplication (防止在部分厂商系统上因 context 空指针崩溃)
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
