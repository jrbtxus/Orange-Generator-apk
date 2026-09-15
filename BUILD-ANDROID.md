# 安卓 APK 构建说明

这个仓库除了是网页版「酸橙味照片生成器」，还带了一套 **Capacitor 7 安卓壳**，
用来产出可以直接装到手机上的 APK。这份文档讲的是**壳与构建**，网页本身的功能见 `README.md`。

- 应用名：`贴贴岛`
- applicationId：`io.github.jrbtxus.orangegenerator`
- 产物：`app-debug.apk` / `app-release.apk`（两者使用**同一套固定签名**，可互相覆盖升级）

---

## 1. 为什么是 Capacitor，不是 Electron

Electron **没有 Android 运行时**，它的产物是桌面可执行文件，装不进 APK。
Capacitor 用的是系统 WebView + 原生插件，承载的**就是同一份 Rsbuild 产物**（`dist/`），
是安卓上位置最接近 Electron 的方案。代价是 WebView 缺的能力要自己补：
**保存图片到相册**（`SaveToGalleryPlugin`）与**返回键**（`@capacitor/app`）。

```
rsbuild build            →  dist/            （网页产物，和浏览器版完全同源）
cap sync android         →  android/app/src/main/assets/public/
                                    ↓
                          Gradle 打包 + 固定签名
                                    ↓
                     app-debug.apk / app-release.apk
```

---

## 2. 云端构建（推荐，本机不需要 Android SDK）

推送到 `main` 会自动触发，也可以在 Actions 页面手动跑（`Build APK` → `Run workflow`）。
一次构建约 3~5 分钟，产出两个独立 artifact：

| artifact | 内容 |
| --- | --- |
| `orange-generator-debug-<sha>` | `app-debug.apk`（可调试，`chrome://inspect` 能挂） |
| `orange-generator-release-<sha>` | `app-release.apk`（去掉 debuggable，功能一致） |

```bash
gh run list --repo jrbtxus/Orange-Generator-apk
gh run watch <run-id> --repo jrbtxus/Orange-Generator-apk
gh run download <run-id> --repo jrbtxus/Orange-Generator-apk \
  -n orange-generator-release-<sha> -D /tmp/apk
```

工作流里做的检查（任何一条不过就失败，不会产出「看起来成功但装不上」的包）：

1. `npm ci` → `npm run typecheck` → `npm run cap:sync`；
2. keystore 注入后按 **sha256 校验文件指纹**，不是本项目那一份就直接拒绝签名；
3. `apksigner verify --print-certs` 分别验 debug 与 release，并断言两者签名一致；
4. 上传前断言 `out/` 里**只有 `*.apk`**，防止签名材料混进 artifact。

---

## 3. 本机构建（需要 JDK 21 + Android SDK）

```bash
npm ci
npm run cap:sync                 # rsbuild build && cap sync android
cd android && ./gradlew assembleDebug
```

### 推 CI 之前先跑一遍本地校验

```bash
npm run verify:android
```

它做两件事，都是「不跑就会白等 CI 一轮」的那种：

1. **资源静态检查**（`scripts/check-android-res.py`，不需要任何 SDK）：所有 XML 是否合法
   + 资源引用（`@drawable/...`、`@color/...`）是否都能对上。
   > 踩过的坑：XML 注释里出现连续两个短横线是**非法**的。把 CSS 变量名 `--sky` 写进
   > `colors.xml` 的注释，aapt 会报 `The string "--" is not permitted within comments`
   > 让整个构建失败，而这个错只有真正编译资源时才暴露。
2. **Java 编译校验**（`scripts/verify-android.sh` 后半段）：用 ECJ + Capacitor core 的 AAR
   编译 `SaveToGalleryPlugin.java` / `MainActivity.java`。
   没有 `android.jar` 时这一步会**自动跳过**（CI 里是真编译）；
   想跑就指定一份：`ANDROID_JAR=/path/to/platforms/android-35/android.jar npm run verify:android`。

要复用同一套固定签名，就在 `android/keystore.properties`（已 gitignore）里写：

```properties
storeFile=/绝对路径/orange-release.jks
storePassword=***
keyAlias=orange
keyPassword=***
```

不配也可以：debug 会回落到 Android 默认 debug keystore，release 产出 unsigned 包。

### 改了网页代码之后

`android/app/src/main/assets/public/` 是 `cap sync` 生成物（已 gitignore），**不要手改**。
每次改完网页代码都要重新 `npm run cap:sync` 再打包，否则 APK 里还是旧页面。

---

## 4. 签名模型

固定签名意味着**私钥必须放在某处**。本项目把它放在 **GitHub Secrets**
（base64 的 PKCS12 + 口令），仓库里没有任何私钥材料。需要的 Secrets：

| Secret | 说明 |
| --- | --- |
| `ORANGE_KEYSTORE_BASE64` | `base64 -w0 orange-release.jks` |
| `ORANGE_KEYSTORE_PASSWORD` | keystore 口令 |
| `ORANGE_KEY_ALIAS` | key 别名（`orange`） |
| `ORANGE_KEY_PASSWORD` | key 口令（PKCS12 下与 store 口令相同） |
| `ORANGE_KEYSTORE_FILENAME` | 可选，落盘文件名，默认 `orange-release.jks` |

**真实风险**：谁能改本仓库 workflow，谁就能让 CI 把私钥打出来 —— 不要随意加协作者。
不接受私钥离开本机的话，唯一替代是**本机签名**（CI 只出 unsigned 包）。

换钥（keystore 丢失或轮换）而 applicationId 不变时，**用户必须先卸载再装**，本地数据会清空。

---

## 5. 权限模型（相册为什么不要权限）

`SaveToGalleryPlugin` 把成品写进 `Pictures/贴贴岛/`：

- **Android 10 (API 29) 及以上**：通过 MediaStore 写「自己创建的媒体」，属于分区存储允许的操作，
  **不需要任何运行时权限**，系统不弹框，系统管家里也看不到存储权限条目——这是正常的。
- **Android 9 及以下**：只能直接写公共外部存储，需要 `WRITE_EXTERNAL_STORAGE`
  （manifest 里带 `maxSdkVersion="28"`，在 Android 10+ 上根本不会安装）。

写入之后插件会**按 URI 回读 MediaStore 的 SIZE** 与真实字节数比对，对不上就报错，
所以前端拿到的 `ok: true` 表示「照片确实在相册里」，不会再出现「提示保存成功但相册里找不到」。

---

## 6. 图标与启动图

```bash
npm run icon:android        # 需要 python3 + Pillow
```

由 `public/assets/stickers/orange-4.png` 生成 `mipmap-*/ic_launcher*.png` 与
`drawable-nodpi/splash_art.png`，产物**随仓库提交**（CI 不重新生成：不同 Pillow 版本的
缩放结果不完全一致，放 CI 里会变成随机失败）。

启动画面是 `android:windowBackground`（`drawable/splash.xml`）：竖向渐变铺满 +
居中主视觉。渐变取色与页面 `body` / `.loading-layer` 的
`linear-gradient(180deg,#92d2f6 0%,#92d2f6 62%,#ffffff 100%)` 同色，
所以启动图淡出、WebView 首帧出现时不闪白。

> 注意：Capacitor **不会**调用 `installSplashScreen()`，`Theme.SplashScreen` 的
> `windowSplashScreenAnimatedIcon` 之类属性从来没生效过，别往那个方向改。

---

## 7. 排障

| 现象 | 原因 / 处理 |
| --- | --- |
| 点「保存图片」没反应、相册里也没有 | 平台判定挂了。判据必须是 `window.androidBridge`（`Capacitor.getPlatform()` 的真实来源），**不要**用自造标志位——历史上因此让整条相册链路静默失效过 |
| 提示保存成功但相册找不到 | 已由「写后回读 SIZE 校验」堵住；若仍出现，看提示里的真实路径与错误码 |
| 上传图片按钮没反应 | `<input type=file>` 走系统选择器，不需要权限；确认 `android:configChanges` 没被改动 |
| 构建报 `Failed to find package 'tools'` | 不要用 `android-actions/setup-android@v3`，runner 自带 SDK，删掉该步骤 |
| workflow 直接 fail 且只说 "workflow file issue" | YAML 缩进被破坏。本地先 `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build-apk.yml'))"` 再推 |
| 构建报「debug 与 release 签名不一致」 | 验签步骤别绑定 `Signer #1` 前缀（build-tools 37 输出的是 `V3.0 Signer:`），只按字段名匹配 |

---

## 8. 已知限制

1. **横屏被锁**（`android:screenOrientation="portrait"`）。需要横屏就把这行删掉。
2. **壳内分享按钮不出现**：`navigator.share` 在 Android WebView 里不存在，
   所以分享入口只在浏览器里显示；壳内用「保存到相册」。
3. **release 未开混淆**（`minifyEnabled false`），与 debug 功能一致，只是去掉 debuggable。
4. **debug 与 release 不能同时安装**（同 applicationId + 同签名）。
5. 首次启动会加载 Google Analytics 与外部字体等资源；离线时页面本体照常可用。
