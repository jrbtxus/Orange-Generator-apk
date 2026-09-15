import { App as CapacitorApp } from "@capacitor/app";
import {
  Capacitor,
  registerPlugin,
  type PluginListenerHandle,
} from "@capacitor/core";

/**
 * 安卓壳（Capacitor）侧的原生能力封装。
 *
 * 为什么需要这一层（踩过的坑，别再踩回去）：
 *   1. WebView 里的 `<a download>` / `URL.createObjectURL` 不会把文件落盘到相册，
 *      所以「保存图片」在壳里必须走原生插件写 MediaStore。
 *   2. 平台判定**不要**用自造标志位。Capacitor 7 既没有 `window.__capacitorPlatform`，
 *      也不会给 `window.Capacitor` 打标记；它的真实判据是 `window.androidBridge`
 *      （`@capacitor/core` 的 `getPlatformId()` 就是看它）。用错判据的后果是
 *      「分支永远为 false」——功能静默失效、测试却全绿。
 */

export interface NativeSaveResult {
  ok: boolean;
  code?: string;
  error?: string;
  uri?: string;
  location?: string;
  filename?: string;
  bytes?: number;
  verified?: boolean;
  via?: string;
  appCopyPath?: string;
  appCopyError?: string;
  sdkInt?: number;
}

export interface NativeProbeResult {
  ok: boolean;
  code?: string;
  error?: string;
  sdkInt?: number;
  needsStoragePermission?: boolean;
  permissionState?: string;
  albumDir?: string;
  canWriteSilently?: boolean;
}

export interface NativeOpenResult {
  ok: boolean;
  opened?: boolean;
  error?: string;
}

interface SaveToGalleryPlugin {
  saveImage(options: {
    base64: string;
    filename?: string;
  }): Promise<NativeSaveResult>;
  probe(): Promise<NativeProbeResult>;
  openImage(options: { uri: string }): Promise<NativeOpenResult>;
}

const SaveToGallery = registerPlugin<SaveToGalleryPlugin>("SaveToGallery");

/** 相册里的子目录名，与 SaveToGalleryPlugin.java 的 ALBUM_DIR 保持一致。 */
export const GALLERY_ALBUM = "贴贴岛";

/** 是否运行在 Capacitor 的安卓壳里（浏览器 / Electron 里为 false）。 */
export function isAndroidApp(): boolean {
  if (typeof window === "undefined") return false;
  try {
    if (Capacitor.getPlatform() === "android") return true;
  } catch {
    // 某些精简环境下 Capacitor 不可用，继续用下面的兜底判据
  }
  return Boolean(
    (window as unknown as { androidBridge?: unknown }).androidBridge
  );
}

/**
 * 把 dataURL 交给原生插件写进系统相册。
 * 插件写完会回读 MediaStore 的 SIZE 校验，所以 `ok: true` 表示「真的在相册里了」。
 */
export async function saveImageToGallery(
  dataUrl: string,
  filename: string
): Promise<NativeSaveResult> {
  if (!isAndroidApp()) {
    return { ok: false, code: "NOT_NATIVE", error: "当前环境不是安卓壳" };
  }
  try {
    return await SaveToGallery.saveImage({ base64: dataUrl, filename });
  } catch (error) {
    return {
      ok: false,
      code: "PLUGIN_ERROR",
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

/** 用系统看图应用打开刚保存的那张图。 */
export async function openSavedImage(uri: string): Promise<NativeOpenResult> {
  if (!isAndroidApp()) {
    return { ok: false, error: "当前环境不是安卓壳" };
  }
  try {
    return await SaveToGallery.openImage({ uri });
  } catch (error) {
    return {
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

/**
 * 启动自检：只读地确认插件在不在、这台设备要不要存储权限、相册目录是什么。
 * 结果挂到 `window.__tietiedaoNative` 上，方便用 chrome://inspect 现场排查。
 */
export async function installNativeStartupCheck(): Promise<void> {
  if (!isAndroidApp()) return;
  let result: NativeProbeResult;
  try {
    result = await SaveToGallery.probe();
  } catch (error) {
    result = {
      ok: false,
      code: "PROBE_FAILED",
      error: error instanceof Error ? error.message : String(error),
    };
  }
  (window as unknown as { __tietiedaoNative?: unknown }).__tietiedaoNative = result;
  if (result.ok) {
    console.info(
      `[贴贴岛] 相册插件就绪：SDK ${result.sdkInt}，相册目录 ${result.albumDir}，` +
        `${result.canWriteSilently ? "零权限写入" : "需要存储权限"}`
    );
  } else {
    console.warn(`[贴贴岛] 相册插件自检失败：${result.error ?? "未知原因"}`);
  }
}

/**
 * 接管安卓返回键。
 *
 * handler 返回 true 表示「这次返回我处理掉了」，返回 false 就让壳把应用最小化
 * （编辑中的画布只在内存里，直接退出会丢用户的工作）。
 */
export function registerAndroidBackButton(
  handler: () => boolean
): () => void {
  if (!isAndroidApp()) return () => {};

  let handle: PluginListenerHandle | null = null;
  let disposed = false;

  void CapacitorApp.addListener("backButton", () => {
    if (handler()) return;
    void CapacitorApp.minimizeApp();
  })
    .then((registered) => {
      if (disposed) {
        void registered.remove();
        return;
      }
      handle = registered;
    })
    .catch(() => {
      // 拿不到返回键事件时不影响其它功能（系统会退回默认行为：退出应用）
    });

  return () => {
    disposed = true;
    if (handle) void handle.remove();
  };
}
