package com.tungsten.fcl.ui.download;

import android.content.Context;

import com.tungsten.fcl.BuildConfig;
import com.tungsten.fcl.R;
import com.tungsten.fcl.setting.DownloadProviders;
import com.tungsten.fcl.setting.Profile;
import com.tungsten.fcl.setting.Profiles;
import com.tungsten.fcl.ui.TaskDialog;
import com.tungsten.fcl.util.TaskCancellationAction;
import com.tungsten.fclauncher.utils.FCLPath;
import com.tungsten.fclcore.download.GameBuilder;
import com.tungsten.fclcore.download.LibraryAnalyzer;
import com.tungsten.fclcore.download.RemoteVersion;
import com.tungsten.fclcore.download.VersionList;
import com.tungsten.fclcore.game.Version;
import com.tungsten.fclcore.task.FileDownloadTask;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.task.TaskExecutor;
import com.tungsten.fclcore.task.TaskListener;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.io.FileUtils;
import com.tungsten.fclcore.util.io.HttpRequest;
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manages automatic downloading / updating of the modpack (module package).
 * <p>
 * The modpack manifest is fetched from {@link #MJY_ROOT} (packages.json). When the
 * player taps the launch button, the manager compares the remote package version with
 * the locally installed one (recorded in mjypks.json). If they differ, every file listed
 * in the manifest is downloaded into the shared common directory while a progress dialog
 * is shown; once finished the game is launched immediately. A resume buffer (mjypksbuf.json)
 * keeps track of already downloaded files so an interrupted download can continue.
 */
public class ModpackManager {

    /**
     * Root URL of the modpack repository (COS bucket).
     * 由 buildType 决定：正式包指向正式桶，fortest 包指向测试桶（见 FCL/build.gradle.kts）。
     */
    public static final String MJY_ROOT = BuildConfig.MODPACK_ROOT;

    private static volatile ModpackManager instance;

    public static ModpackManager getInstance() {
        if (instance == null) {
            synchronized (ModpackManager.class) {
                if (instance == null) {
                    instance = new ModpackManager();
                }
            }
        }
        return instance;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile MjyPackageList mjyPackageList = null;
    private volatile MjyModPackageList mjyModPackageList = null;

    /** Local marker file recording the installed package list/version. */
    private final String fn = FCLPath.SHARED_COMMON_DIR + "/mjypks.json";
    /** Resume buffer written when a download is interrupted. */
    private final String fnbuf = FCLPath.SHARED_COMMON_DIR + "/mjypksbuf.json";

    /** Guards against overlapping update checks (e.g. repeated taps on launch). */
    private volatile boolean busy = false;

    /** 见 {@link #checkUpdateAndLaunch(Context, Runnable, Runnable)}，只在主线程读写。 */
    private Runnable abortAction;

    /** 流程终止且没能启动游戏：解锁 busy 并通知调用方。 */
    private void abortFlow() {
        busy = false;
        Runnable action = abortAction;
        abortAction = null;
        if (action != null) {
            action.run();
        }
    }

    /** 流程终止且正常启动游戏：解锁 busy，不回调 abort。 */
    private void launchNow(Runnable launchAction) {
        busy = false;
        abortAction = null;
        launchAction.run();
    }

    private ModpackManager() {
    }

    // ---------------------------------------------------------------------
    // Remote manifest fetching
    // ---------------------------------------------------------------------

    public CompletableFuture<Void> getMjyPackageList() {
        return HttpRequest
                .GET(MJY_ROOT + "packages.json")
                .accept("application/json")
                .getJsonAsync(MjyPackageList.class)
                .thenAcceptAsync(root -> {
                    lock.writeLock().lock();
                    try {
                        if (root != null) {
                            mjyPackageList = root;
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                });
    }

    public CompletableFuture<Void> getMjyModList(String name) {
        return HttpRequest
                .GET(MJY_ROOT + name)
                .accept("application/json")
                .getJsonAsync(MjyModPackageList.class)
                .thenAcceptAsync(root -> {
                    lock.writeLock().lock();
                    try {
                        if (root != null) {
                            mjyModPackageList = root;
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                });
    }

    private String getPackageLocation() {
        MjyPackageList pk = mjyPackageList;
        if (pk == null || pk.packages == null || pk.packages.isEmpty()) {
            throw new IllegalStateException("Modpack packages.json is empty or invalid");
        }
        return pk.packages.get(0).location;
    }

    private boolean isModpackInfoReady() {
        MjyModPackageList mod = mjyModPackageList;
        return mjyPackageList != null && mod != null && mod.tasks != null;
    }

    /**
     * Resolve the target server address (host:port) declared by the modpack manifest.
     * <p>
     * Mirrors the desktop launcher's behaviour: {@code ManifestInfoEnumerator} copies
     * {@code manifest.server} into the package info and {@code LauncherFrame} passes it
     * to the game as the {@code -DtargetServer} system property. Custom mods read that
     * property to know which server to display / connect to.
     *
     * @return the server address, or {@code null} when the manifest declares none.
     */
    public String getServer() {
        MjyModPackageList mod = mjyModPackageList;
        if (mod != null && mod.server != null && !mod.server.trim().isEmpty()) {
            return mod.server;
        }
        MjyPackageList pk = mjyPackageList;
        if (pk != null && pk.packages != null && !pk.packages.isEmpty()
                && pk.packages.get(0).server != null && !pk.packages.get(0).server.trim().isEmpty()) {
            return pk.packages.get(0).server;
        }
        // Fall back to the locally persisted package list so the server address is still
        // available when launching offline (remote manifest unreachable).
        try {
            File mjypks = new File(fn);
            if (mjypks.exists()) {
                MjyPackageList local = JsonUtils.GSON.fromJson(FileUtils.readText(mjypks), MjyPackageList.class);
                if (local != null && local.packages != null && !local.packages.isEmpty()
                        && local.packages.get(0).server != null && !local.packages.get(0).server.trim().isEmpty()) {
                    return local.packages.get(0).server;
                }
            }
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "Unable to read server from local modpack marker", e);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Version comparison
    // ---------------------------------------------------------------------

    /**
     * @return true when the modpack needs to be (re)downloaded, false when the locally
     * installed version already matches the remote one.
     */
    private boolean isCanUpdateMjyGame() {
        boolean rt = true;
        File mjypks = new File(fn);
        if (mjyPackageList != null && mjypks.exists()) {
            try {
                String str = FileUtils.readText(mjypks);
                MjyPackageList pk = JsonUtils.GSON.fromJson(str, MjyPackageList.class);
                if (pk != null && pk.packages != null && !pk.packages.isEmpty()
                        && pk.packages.get(0).version != null
                        && pk.packages.get(0).version.equals(mjyPackageList.packages.get(0).version)) {
                    rt = false;
                }
            } catch (Exception e) {
                Logging.LOG.log(Level.WARNING, "Unable to read local modpack marker", e);
                rt = false;
            }
        }
        return rt;
    }

    // ---------------------------------------------------------------------
    // Download task building
    // ---------------------------------------------------------------------

    public Task<Void> saveMjyModAsync(MjyModTask mod, String stage) {
        File file = new File(FCLPath.SHARED_COMMON_DIR + "/" + mod.to);
        List<URL> urls = Profiles.getSelectedProfile().getDependency().getDownloadProvider()
                .injectURLWithCandidates(MJY_ROOT + "objects/" + mod.location);
        return new FileDownloadTask(urls, file)
                .withStage(stage)
                .thenRunAsync(() -> {
                    lock.readLock().lock();
                    try {
                        mod.ok = true;
                    } finally {
                        lock.readLock().unlock();
                    }
                });
    }

    public Task<?> buildMjyAsync() {
        String stage = "fcl.modpack.download";
        List<String> stages = new ArrayList<>();
        stages.add(stage);

        Task<?> chain = Task.runAsync(() -> {
        });
        for (MjyModTask task : mjyModPackageList.tasks) {
            if (task.to != null && !task.ok) {
                chain = chain.thenComposeAsync(() -> saveMjyModAsync(task, stage));
            }
        }

        return chain
                .thenComposeAsync(new MjyVersionJsonSaveTask(fn, mjyPackageList))
                .whenComplete(exception -> {
                    File json = new File(fnbuf);
                    if (exception != null) {
                        FileUtils.writeText(json, JsonUtils.GSON.toJson(mjyModPackageList));
                    } else {
                        if (json.exists()) {
                            FileUtils.forceDelete(json);
                        }
                    }
                })
                .withStagesHint(stages);
    }

    public final class MjyVersionJsonSaveTask extends Task<MjyPackageList> {

        private final String filename;
        private final MjyPackageList version;

        public MjyVersionJsonSaveTask(String filename, MjyPackageList version) {
            this.filename = filename;
            this.version = version;
            setSignificance(TaskSignificance.MODERATE);
            setResult(version);
        }

        @Override
        public void execute() throws Exception {
            try {
                File json = new File(filename);
                FileUtils.writeText(json, JsonUtils.GSON.toJson(version));
            } catch (Exception e) {
                Logging.LOG.log(Level.WARNING, "Unable to write modpack marker file", e);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Resume buffer helpers
    // ---------------------------------------------------------------------

    private void saveMjyBuf() {
        try {
            FileUtils.writeText(new File(fnbuf), JsonUtils.GSON.toJson(mjyModPackageList));
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "Unable to write modpack resume buffer", e);
        }
    }

    private void deleteMjyBuf() {
        File json = new File(fnbuf);
        if (json.exists()) {
            try {
                FileUtils.forceDelete(json);
            } catch (Exception e) {
                Logging.LOG.log(Level.WARNING, "Unable to delete modpack resume buffer", e);
            }
        }
    }

    /**
     * Restore the download progress buffer (if any) so an interrupted download can resume.
     * The buffer is only honoured when it belongs to the same manifest version that is
     * about to be downloaded.
     */
    private void restoreMjyBuf() {
        String currentVersion = mjyModPackageList.version;
        File buf = new File(fnbuf);
        if (buf.exists()) {
            try {
                String str = FileUtils.readText(buf);
                MjyModPackageList loaded = JsonUtils.GSON.fromJson(str, MjyModPackageList.class);
                if (loaded != null && loaded.tasks != null
                        && currentVersion != null && currentVersion.equals(loaded.version)) {
                    mjyModPackageList = loaded;
                }
            } catch (Exception e) {
                Logging.LOG.log(Level.WARNING, "Unable to read modpack resume buffer", e);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Public entry point
    // ---------------------------------------------------------------------

    /**
     * Determine whether the currently selected version is a proper install of this
     * modpack, i.e. a Forge build of the exact game version the manifest targets.
     * <p>
     * A leftover / stale version (for example a plain vanilla install from a previously
     * interrupted setup) would otherwise be launched as-is, producing a game without
     * Forge and without any of the modpack's mods. When this returns {@code false} the
     * caller reinstalls the game body from scratch.
     */
    private boolean isGameReady(String modpackGameVersion) {
        try {
            Profile profile = Profiles.getSelectedProfile();
            String selectedVersion = profile.getSelectedVersion();
            if (selectedVersion == null || !profile.getRepository().hasVersion(selectedVersion)) {
                return false;
            }
            String installedGameVersion = profile.getRepository().getGameVersion(selectedVersion).orElse(null);
            Version resolved = profile.getRepository().getResolvedVersion(selectedVersion);
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(resolved, installedGameVersion);
            return analyzer.has(LibraryAnalyzer.LibraryType.FORGE)
                    && modpackGameVersion != null
                    && modpackGameVersion.equals(installedGameVersion);
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "Unable to verify the installed game version, will reinstall", e);
            return false;
        }
    }

    /**
     * Check the remote modpack, download / update it when necessary (showing a progress
     * dialog), and run {@code launchAction} as soon as the modpack is ready.
     * <p>
     * If the remote manifest cannot be fetched (e.g. no network) the game is launched
     * anyway with whatever content is already present, so offline play keeps working.
     */
    public boolean checkUpdateAndLaunch(Context context, Runnable launchAction) {
        return checkUpdateAndLaunch(context, launchAction, null);
    }

    /**
     * @param onAbort 流程结束但**没有**启动游戏时回调（失败、取消、没有可用版本）。
     *                调用方靠它收起自己的 loading 遮罩 —— 启动成功的那条路不会回调，
     *                因为那时游戏 Activity 已经顶上来了。
     * @return false 表示上一次流程还没跑完、本次调用被忽略（此时 onAbort **不会**被调用，
     *         调用方要自己把刚挂上的遮罩收回去，否则界面就永久锁死了）
     */
    public boolean checkUpdateAndLaunch(Context context, Runnable launchAction, Runnable onAbort) {
        if (busy) {
            return false;
        }
        busy = true;
        abortAction = onAbort;

        // The modpack files are downloaded into the shared .minecraft directory, so the
        // game must run from that same directory rather than an isolated per-version
        // folder; otherwise none of the downloaded mods/configs would be loaded and a
        // "bare" game would start. Legacy builds defaulted to the shared directory -
        // force it here so a previously persisted "isolate game dir" setting cannot
        // launch the wrong (mod-less) game.
        Profiles.getSelectedProfile().getGlobal().setIsolateGameDir(false);

        getMjyPackageList()
                .thenCompose(unused -> getMjyModList(getPackageLocation()))
                .whenComplete((r, e) -> Schedulers.androidUIThread().execute(() -> {
                    if (e == null && isModpackInfoReady()) {
                        if (!isGameReady(mjyModPackageList.gameVersion)) {
                            // No proper Forge install of the modpack is currently selected
                            // (e.g. a stale vanilla version left over from an interrupted
                            // setup): install the game body (+Forge) first, then the modpack.
                            installGameThenModpack(context, launchAction);
                        } else if (!isCanUpdateMjyGame()) {
                            // Already up to date: launch immediately.
                            deleteMjyBuf();
                            launchNow(launchAction);
                        } else {
                            installMjyGame(context, launchAction);
                        }
                    } else {
                        // Could not fetch update info: fall back to launching directly.
                        Logging.LOG.log(Level.WARNING, "Failed to fetch modpack info, launching anyway", e);
                        launchNow(launchAction);
                    }
                }));
        return true;
    }

    /**
     * Install the Minecraft game body (plus the latest matching Forge) because no version
     * is currently installed. On success the modpack download is triggered, followed by
     * the launch action. Mirrors the legacy InstallVersionPage.InstallGame() flow.
     */
    private void installGameThenModpack(Context context, Runnable launchAction) {
        try {
            String gameVersion = mjyModPackageList.gameVersion;
            String title = mjyPackageList.packages.get(0).title;
            String name = (title == null || title.trim().isEmpty()) ? gameVersion : title;

            VersionList<?> forgeList = DownloadProviders.getDownloadProvider().getVersionListById("forge");
            forgeList.refreshAsync(gameVersion).whenComplete((result, exception) -> Schedulers.androidUIThread().execute(() -> {
                if (exception == null) {
                    List<RemoteVersion> forgeVersions = forgeList.getVersions(gameVersion).stream()
                            .sorted()
                            .collect(Collectors.toList());
                    if (forgeVersions.isEmpty()) {
                        Logging.LOG.log(Level.WARNING, "No forge version available for game " + gameVersion);
                        abortFlow();
                        showInstallFailedDialog(context);
                    } else {
                        installGame(context, name, gameVersion, forgeVersions.get(0), launchAction);
                    }
                } else {
                    Logging.LOG.log(Level.WARNING, "Failed to fetch forge version list", exception);
                    abortFlow();
                    showInstallFailedDialog(context);
                }
            }));
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "Failed to install game", e);
            abortFlow();
            showInstallFailedDialog(context);
        }
    }

    private void installGame(Context context, String name, String gameVersion, RemoteVersion forge, Runnable launchAction) {
        try {
            // Remove any stale version with the same name (e.g. a leftover vanilla or a
            // half-installed build) so the fresh Forge install cannot collide with it.
            if (Profiles.getSelectedProfile().getRepository().hasVersion(name)) {
                Profiles.getSelectedProfile().getRepository().removeVersionFromDisk(name);
            }

            GameBuilder builder = Profiles.getSelectedProfile().getDependency().gameBuilder();
            builder.name(name);
            builder.gameVersion(gameVersion);
            builder.version(forge);

            Task<Void> task = builder.buildAsync()
                    .whenComplete(any -> Profiles.getSelectedProfile().getRepository().refreshVersions())
                    .thenRunAsync(Schedulers.androidUIThread(), () -> Profiles.getSelectedProfile().setSelectedVersion(name));

            TaskDialog pane = new TaskDialog(context, TaskCancellationAction.NORMAL);
            pane.setTitle(context.getString(R.string.install_new_game));

            TaskExecutor executor = task.executor(new TaskListener() {
                @Override
                public void onStop(boolean success, TaskExecutor executor) {
                    Schedulers.androidUIThread().execute(() -> {
                        if (success) {
                            // Game body installed: now download the modpack, then launch.
                            installMjyGame(context, launchAction);
                        } else {
                            abortFlow();
                            if (executor.getException() != null) {
                                showInstallFailedDialog(context);
                            }
                        }
                    });
                }
            });
            pane.setExecutor(executor);
            pane.show();
            executor.start();
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "Failed to install game", e);
            abortFlow();
            showInstallFailedDialog(context);
        }
    }

    private void installMjyGame(Context context, Runnable launchAction) {
        try {
            restoreMjyBuf();

            Task<?> task = buildMjyAsync();
            TaskDialog pane = new TaskDialog(context, TaskCancellationAction.NORMAL);
            MjyManifestInfo p = mjyPackageList.packages.get(0);
            String dialogTitle = context.getString(R.string.modpack_update);
            if (p.title != null) {
                dialogTitle = dialogTitle + " - " + p.title + (p.version != null ? " " + p.version : "");
            }
            pane.setTitle(dialogTitle);

            TaskExecutor executor = task.executor(new TaskListener() {
                @Override
                public void onStop(boolean success, TaskExecutor executor) {
                    Schedulers.androidUIThread().execute(() -> {
                        if (success) {
                            launchNow(launchAction);
                        } else {
                            abortFlow();
                            saveMjyBuf();
                            if (executor.getException() != null) {
                                showInstallFailedDialog(context);
                            }
                        }
                    });
                }
            });
            pane.setExecutor(executor);
            pane.show();
            executor.start();
        } catch (Exception e) {
            // Never leave the launch button permanently blocked: fall back to launching.
            Logging.LOG.log(Level.WARNING, "Failed to start modpack download, launching anyway", e);
            launchNow(launchAction);
        }
    }

    private void showInstallFailedDialog(Context context) {
        FCLAlertDialog.Builder builder = new FCLAlertDialog.Builder(context);
        builder.setCancelable(false);
        builder.setAlertLevel(FCLAlertDialog.AlertLevel.ALERT);
        builder.setTitle(context.getString(R.string.install_failed));
        builder.setMessage(context.getString(R.string.install_failed_downloading));
        builder.setNegativeButton(context.getString(com.tungsten.fcllibrary.R.string.dialog_positive), () -> {
        });
        builder.create().show();
    }
}
