package com.tungsten.fcl.setting;

import static com.tungsten.fcl.util.FXUtils.onInvalidating;
import static com.tungsten.fclcore.fakefx.collections.FXCollections.observableArrayList;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.tungsten.fclauncher.utils.FCLPath;
import com.tungsten.fclcore.fakefx.beans.Observable;
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyListProperty;
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyListWrapper;
import com.tungsten.fclcore.fakefx.collections.ObservableList;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.gson.fakefx.factories.JavaFxPropertyTypeAdapterFactory;
import com.tungsten.fclcore.util.io.FileUtils;
import com.tungsten.fclcore.util.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Controllers {

    private Controllers() {
    }

    private static final ObservableList<Controller> controllers = observableArrayList(controller -> new Observable[]{controller});
    private static final ReadOnlyListWrapper<Controller> controllersWrapper = new ReadOnlyListWrapper<>(controllers);
    public static Controller DEFAULT_CONTROLLER;

    private static final List<Runnable> CALLBACKS = new ArrayList<>();

    private static final String PRESET_CONTROLLER_ASSET = "/assets/controllers/00000000.json";

    private static Controller readPresetController() throws IOException {
        String str = IOUtils.readFullyAsString(Controllers.class.getResourceAsStream(PRESET_CONTROLLER_ASSET));
        return new GsonBuilder()
                .registerTypeAdapterFactory(new JavaFxPropertyTypeAdapterFactory(true, true))
                .setPrettyPrinting()
                .create().fromJson(str, Controller.class);
    }

    /**
     * X 计划内置按键布局：assets 里的 00000000.json 就是要预置给玩家的方案，
     * 而 {@link VersionSetting} 的 controller 默认值也是 "00000000"，所以玩家装上就能直接用，不用自己配置。
     * <p>
     * 只靠 {@link #checkControllers()} 只能覆盖全新安装（它仅在一个布局都没有时才写盘）；
     * 已经装过旧版本的玩家磁盘上留着旧的默认布局，因此这里按 versionCode 对账：
     * 内置的比磁盘上的新就整份覆盖。以后更新预置布局，记得同时把 assets 里的 versionCode 调大。
     */
    private static void syncPresetController() {
        try {
            Controller preset = readPresetController();
            if (preset == null) {
                throw new JsonParseException("Preset controller is null!");
            }
            DEFAULT_CONTROLLER = preset;
            Controller current = controllers.stream()
                    .filter(it -> it != null && preset.getId().equals(it.getId()))
                    .findFirst().orElse(null);
            if (current == null) {
                preset.saveToDisk();
                controllers.add(preset);
            } else if (current.getVersionCode() < preset.getVersionCode()) {
                preset.saveToDisk();
                controllers.set(controllers.indexOf(current), preset);
            }
        } catch (IOException | JsonParseException e) {
            Logging.LOG.log(Level.SEVERE, "Failed to sync preset controller!", e.getMessage());
        }
    }

    public static void checkControllers() {
        if (controllers.contains(null)) {
            controllers.remove(null);
        }
        if (controllers.isEmpty()) {
            try {
                if (DEFAULT_CONTROLLER == null) {
                    DEFAULT_CONTROLLER = readPresetController();
                }
                DEFAULT_CONTROLLER.saveToDisk();
            } catch (IOException e) {
                Logging.LOG.log(Level.SEVERE, "Failed to generate default controller!", e.getMessage());
            }
            controllers.addAll(getControllersFromDisk());
        }
    }

    /**
     * True if {@link #init()} hasn't been called.
     */
    private static boolean initialized = false;

    public static boolean isInitialized() {
        return initialized;
    }

    private static void updateControllerStorages() {
        // don't update the underlying storage before data loading is completed
        // otherwise it might cause data loss
        if (!initialized)
            return;
        // update storage
        File[] files = new File(FCLPath.CONTROLLER_DIR).listFiles();
        if (files != null) {
            ArrayList<String> fileNames = (ArrayList<String>) controllers.stream().map(Controller::getFileName).collect(Collectors.toList());
            for (File file : files) {
                if (((file.isDirectory() && !file.getName().equals("styles") && !file.getName().equals("input")) || !fileNames.contains(file.getName())) && !file.getName().endsWith(".bak")) {
                    file.delete();
                }
            }
        }
        for (Controller controller : controllers) {
            controller.saveToDisk();
        }
    }

    static {
        controllers.addListener(onInvalidating(Controllers::updateControllerStorages));
        controllers.addListener(onInvalidating(Controllers::checkControllers));
    }

    public static void init() {
        if (initialized)
            return;

        controllers.addAll(getControllersFromDisk());
        syncPresetController();
        checkControllers();

        initialized = true;
        CALLBACKS.forEach(callback -> Schedulers.androidUIThread().execute(callback));
        CALLBACKS.clear();
    }

    private static ArrayList<Controller> getControllersFromDisk() {
        ArrayList<Controller> list = new ArrayList<>();
        List<File> jsons = FileUtils.listFilesByExtension(new File(FCLPath.CONTROLLER_DIR), "json");
        for (File json : jsons) {
            if (json.isFile()) {
                try {
                    String str = FileUtils.readText(json);
                    Controller controller = new GsonBuilder()
                            .registerTypeAdapterFactory(new JavaFxPropertyTypeAdapterFactory(true, true))
                            .setPrettyPrinting()
                            .create().fromJson(str, Controller.class);
                    if (controller == null) {
                        throw new JsonParseException("Controller is null!");
                    }
                    if (!json.getName().equals(controller.getFileName())) {
                        controller.renameFile(json.getName(), controller.getFileName());
                    }
                    list.add(controller);
                } catch (IOException e) {
                    Logging.LOG.log(Level.WARNING, "Can't read file: " + json.getAbsolutePath(), e.getMessage());
                } catch (JsonParseException e) {
                    Logging.LOG.log(Level.WARNING, "File: " + json.getAbsolutePath(), e.getMessage() + " is broken!");
                    json.renameTo(new File(FCLPath.CONTROLLER_DIR, json.getName() + ".bak"));
                }
            }
        }
        return list;
    }

    public static ObservableList<Controller> getControllers() {
        if (controllers.contains(null)) {
            controllers.remove(null);
        }
        if (controllers.isEmpty()) controllers.add(DEFAULT_CONTROLLER);
        return controllers;
    }

    public static ReadOnlyListProperty<Controller> controllersProperty() {
        return controllersWrapper.getReadOnlyProperty();
    }

    public static void addController(Controller controller) {
        if (!initialized) return;
        controllers.add(controller);
    }

    public static void removeControllers(Controller controller) {
        if (!initialized) return;
        controllers.remove(controller);
    }

    public static Controller findControllerById(String id) {
        checkControllers();
        return controllers.stream().filter(it -> it.getId().equals(id)).findFirst().orElse(controllers.get(0));
    }

    public static void addCallback(Runnable callback) {
        if (initialized) {
            callback.run();
            return;
        }
        CALLBACKS.add(callback);
    }

}
