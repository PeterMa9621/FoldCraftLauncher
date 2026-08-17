package com.tungsten.fcl.upgrade;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.google.gson.reflect.TypeToken;
import com.tungsten.fcl.R;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.io.NetworkUtils;
import com.tungsten.fcllibrary.util.LocaleUtils;

import java.util.ArrayList;

public class UpdateChecker {

    /**
     * X 计划定制版禁用启动器自更新：上游 version_map.json 指向的是官方 FCL 的 APK，
     * 玩家一升级就离开了定制版。入口（主页自动检查、设置页"检查更新"按钮）已经删掉，
     * 这里再兜一层，任何残留调用都直接空转，玩家没有开关可以自行打开。
     */
    public static final boolean UPDATE_ENABLED = false;

    public static final String UPDATE_CHECK_URL = "https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/main/version_map.json";
    public static final String UPDATE_CHECK_URL_CN = "https://gitee.com/fcl-team/FCL-Repo/raw/main/res/version_map.json";

    private static UpdateChecker instance;

    public static UpdateChecker getInstance() {
        if (instance == null) {
            instance = new UpdateChecker();
        }
        return instance;
    }

    private boolean isChecking = false;

    public boolean isChecking() {
        return isChecking;
    }

    public UpdateChecker() {

    }

    public Task<?> checkManually(Context context) {
        return check(context, true, true);
    }

    public Task<?> checkAuto(Context context) {
        return check(context, false, false);
    }

    public Task<?> check(Context context, boolean showBeta, boolean showAlert) {
        return Task.runAsync(() -> {
            if (!UPDATE_ENABLED) {
                isChecking = false;
                return;
            }
            isChecking = true;
            if (showAlert) {
                Schedulers.androidUIThread().execute(() -> Toast.makeText(context, context.getString(R.string.update_checking), Toast.LENGTH_SHORT).show());
            }
            String res = NetworkUtils.doGet(NetworkUtils.toURL(LocaleUtils.isChinese(context) ? UPDATE_CHECK_URL_CN : UPDATE_CHECK_URL));
            ArrayList<RemoteVersion> versions = JsonUtils.GSON.fromJson(res, new TypeToken<ArrayList<RemoteVersion>>(){}.getType());
            for (RemoteVersion version : versions) {
                if (version.getVersionCode() > getCurrentVersionCode(context)) {
                    if (showBeta || !version.isBeta()) {
                        if (showBeta || !isIgnore(context, version.getVersionCode())) {
                            showUpdateDialog(context, version);
                        }
                        isChecking = false;
                        return;
                    }
                }
            }
            if (showAlert) {
                Schedulers.androidUIThread().execute(() -> Toast.makeText(context, context.getString(R.string.update_not_exist), Toast.LENGTH_SHORT).show());
            }
            isChecking = false;
        });
    }

    public static int getCurrentVersionCode(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            throw new IllegalStateException("Can't get current version code");
        }
    }

    private void showUpdateDialog(Context context, RemoteVersion version) {
        Schedulers.androidUIThread().execute(() -> {
            UpdateDialog dialog = new UpdateDialog(context, version);
            dialog.show();
        });
    }

    public static boolean isIgnore(Context context, int code) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        return sharedPreferences.getInt("ignore_update", -1) == code;
    }

    public static void setIgnore(Context context, int code) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        @SuppressLint("CommitPrefEdits") SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("ignore_update", code);
        editor.apply();
    }

}
