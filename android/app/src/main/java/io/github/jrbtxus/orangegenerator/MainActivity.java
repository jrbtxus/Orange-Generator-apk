package io.github.jrbtxus.orangegenerator;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 自定义插件必须手动注册（Capacitor 只会自动注册依赖里的插件）
        registerPlugin(SaveToGalleryPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
