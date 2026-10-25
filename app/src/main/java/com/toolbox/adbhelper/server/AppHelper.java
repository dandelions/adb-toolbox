package com.toolbox.adbhelper.server;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 运行在对端 Android 设备的轻量级 Helper
 * 通过 app_process 执行，能够直接解析 AdaptiveIconDrawable、VectorDrawable、BitmapDrawable 等
 * 并将图标压缩为 PNG Base64，连同应用名称输出给客户端。
 */
public class AppHelper {

    public static void main(String[] args) {
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }

            Context context = getSystemContext();
            if (context == null) {
                System.err.println("ERROR: Unable to get system context");
                System.out.println("DONE");
                return;
            }

            // 支持音量控制子命令：app_process ... AppHelper volume <0..15>
            if (args != null && args.length >= 2 && "volume".equalsIgnoreCase(args[0])) {
                try {
                    int vol = Integer.parseInt(args[1].trim());
                    setVolume(context, vol);
                    System.out.println("VOLUME_RESULT:" + vol);
                } catch (Throwable t) {
                    System.err.println("VOLUME_PARSE_ERR:" + t.getMessage());
                }
                return;
            }

            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                System.err.println("ERROR: Unable to get package manager");
                System.out.println("DONE");
                return;
            }

            if (args != null && args.length > 0 && !args[0].equals("all")) {
                for (String pkgName : args) {
                    processPackage(pm, pkgName.trim());
                }
            } else {
                List<PackageInfo> installedPackages = pm.getInstalledPackages(0);
                for (PackageInfo pkg : installedPackages) {
                    processPackage(pm, pkg.packageName);
                }
            }
        } catch (Throwable t) {
            System.err.println("ERROR: " + t.getMessage());
        } finally {
            System.out.println("DONE");
            System.out.flush();
        }
    }

    private static void processPackage(PackageManager pm, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence labelSeq = pm.getApplicationLabel(appInfo);
            String label = (labelSeq != null) ? labelSeq.toString().trim() : "";

            Drawable icon = pm.getApplicationIcon(appInfo);
            String base64 = "";
            if (icon != null) {
                Bitmap bmp = drawableToBitmap(icon, 96, 96);
                if (bmp != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                    byte[] bytes = baos.toByteArray();
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    bmp.recycle();
                }
            }

            System.out.println("APP:" + packageName + "\t" + label + "\t" + base64);
            System.out.flush();
        } catch (Throwable t) {
            // 忽略个别应用的读取异常，不影响整体流程
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
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
            try {
                Method systemMain = Class.forName("android.app.ActivityThread").getDeclaredMethod("systemMain");
                Object activityThread = systemMain.invoke(null);
                Method getSystemContext = activityThread.getClass().getDeclaredMethod("getSystemContext");
                return (Context) getSystemContext.invoke(activityThread);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static void setVolume(Context context, int volume) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                // STREAM_MUSIC = 3
                am.setStreamVolume(3, volume, 0);
            }
        } catch (Throwable t) {
            System.err.println("SET_VOLUME_ERR: " + t.getMessage());
        }
    }
}
