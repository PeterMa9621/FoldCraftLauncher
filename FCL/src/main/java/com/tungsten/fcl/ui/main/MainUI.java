package com.tungsten.fcl.ui.main;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;

import com.tungsten.fcl.R;
import com.tungsten.fcl.game.TexturesLoader;
import com.tungsten.fcl.setting.Accounts;
import com.tungsten.fclcore.auth.Account;
import com.tungsten.fclcore.fakefx.beans.property.ObjectProperty;
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fcllibrary.component.theme.ThemeEngine;
import com.tungsten.fcllibrary.component.ui.FCLCommonUI;
import com.tungsten.fcllibrary.component.view.FCLUILayout;
import com.tungsten.fcllibrary.skin.SkinRenderer;
import com.tungsten.fcllibrary.skin.SkinViewer;

public class MainUI extends FCLCommonUI {

    private SkinViewer skinViewer;
    private SkinRenderer renderer;

    private ObjectProperty<Account> currentAccount;

    public MainUI(Context context, FCLUILayout parent, int id) {
        super(context, parent, id);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        skinViewer = findViewById(R.id.skin_viewer);
        renderer = new SkinRenderer(getContext());
        skinViewer.setRenderer(renderer, 5f);
        setupSkinDisplay();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (skinViewer != null) {
            if (!ThemeEngine.getInstance().theme.isCloseSkinModel()) {
                skinViewer.setVisibility(View.VISIBLE);
                skinViewer.onResume();
                renderer.updateTexture(renderer.getTexture()[0], renderer.getTexture()[1]);
            } else {
                skinViewer.onPause();
                skinViewer.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (skinViewer != null) {
            skinViewer.onPause();
            skinViewer.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (skinViewer != null && isShowing() && !ThemeEngine.getInstance().theme.isCloseSkinModel()) {
            skinViewer.setVisibility(View.VISIBLE);
            skinViewer.onResume();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (skinViewer != null) {
            skinViewer.onPause();
            skinViewer.setVisibility(View.GONE);
        }
    }

    @Override
    public Task<?> refresh(Object... param) {
        return Task.runAsync(() -> {

        });
    }

    private void setupSkinDisplay() {
        currentAccount = new SimpleObjectProperty<>() {

            @Override
            protected void invalidated() {
                Account account = get();
                renderer.textureProperty().unbind();
                if (account == null) {
                    renderer.updateTexture(BitmapFactory.decodeStream(MainUI.class.getResourceAsStream("/assets/img/alex.png")), null);
                } else {
                    renderer.textureProperty().bind(TexturesLoader.textureBinding(account));
                }
            }
        };
        currentAccount.bind(Accounts.selectedAccountProperty());
    }

    public void refreshSkin(Account account) {
        Schedulers.androidUIThread().execute(() -> {
            if (currentAccount.get() == account) {
                renderer.textureProperty().unbind();
                renderer.textureProperty().bind(TexturesLoader.textureBinding(currentAccount.get()));
            }
        });
    }

}
