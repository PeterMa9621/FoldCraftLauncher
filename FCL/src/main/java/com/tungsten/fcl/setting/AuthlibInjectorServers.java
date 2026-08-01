/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fcl.setting;

import static com.tungsten.fcl.setting.ConfigHolder.config;
import static com.tungsten.fclcore.util.Logging.LOG;

import com.google.gson.JsonParseException;
import com.tungsten.fclauncher.utils.FCLPath;
import com.tungsten.fclcore.auth.authlibinjector.AuthlibInjectorServer;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.gson.TolerableValidationException;
import com.tungsten.fclcore.util.gson.Validation;
import com.tungsten.fclcore.util.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;

public final class AuthlibInjectorServers implements Validation {

    public static final String CONFIG_FILENAME = "authlib-injectors.json";

    /**
     * 默认认证服务器，未配置任何认证服务器时自动添加。
     * 注意：URL 必须以 "/" 结尾（AuthlibInjectorProvider 直接拼接子路径）。
     */
    public static final String DEFAULT_SERVER_URL = "https://littleskin.cn/api/yggdrasil/";

    private static final Set<AuthlibInjectorServer> servers = new CopyOnWriteArraySet<>();

    public static Set<AuthlibInjectorServer> getServers() {
        return servers;
    }

    private final List<String> urls;

    private AuthlibInjectorServers(List<String> urls) {
        this.urls = urls;
    }

    @Override
    public void validate() throws JsonParseException, TolerableValidationException {
        if (this.urls == null) {
            throw new JsonParseException("authlib-injectors.json -> urls cannot be null.");
        }
    }

    private static final Path configLocation = new File(FCLPath.FILES_DIR + "/" + CONFIG_FILENAME).toPath();

    public static void init() {
        if (ConfigHolder.isNewlyCreated() && Files.exists(configLocation)) {
            AuthlibInjectorServers configInstance = null;
            try {
                String content = FileUtils.readText(configLocation);
                configInstance = JsonUtils.GSON.fromJson(content, AuthlibInjectorServers.class);
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, "Malformed authlib-injectors.json", e);
            }

            if (configInstance != null && !configInstance.urls.isEmpty()) {
                config().setPreferredLoginType(Accounts.getLoginType(Accounts.FACTORY_AUTHLIB_INJECTOR));
                for (String url : configInstance.urls) {
                    Task.supplyAsync(Schedulers.io(), () -> AuthlibInjectorServer.locateServer(url))
                            .thenAcceptAsync(Schedulers.androidUIThread(), server -> {
                                config().getAuthlibInjectorServers().add(server);
                                servers.add(server);
                            })
                            .start();
                }
            }
        }
        ensureDefaultServer();
    }

    /**
     * 若当前没有配置任何认证服务器，自动添加默认认证服务器（LittleSkin）。
     * 优先联网解析服务器元数据（名称等），失败时直接以 URL 添加，
     * 元数据会在后续启动时由 {@link Accounts#init()} 自动补全。
     */
    private static void ensureDefaultServer() {
        if (!config().getAuthlibInjectorServers().isEmpty()) {
            return;
        }
        Task.supplyAsync(Schedulers.io(), () -> {
            try {
                return AuthlibInjectorServer.locateServer(DEFAULT_SERVER_URL);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to resolve default auth server, adding it without metadata: " + DEFAULT_SERVER_URL, e);
                return new AuthlibInjectorServer(DEFAULT_SERVER_URL);
            }
        }).thenAcceptAsync(Schedulers.androidUIThread(), server -> {
            if (!config().getAuthlibInjectorServers().contains(server)) {
                config().getAuthlibInjectorServers().add(server);
            }
        }).start();
    }
}
