package io.github.jrbtxus.orangegenerator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把导出的成品图片写进系统相册（Pictures/贴贴岛）。
 *
 * 权限模型（这是本插件最容易踩坑的地方）：
 *   - Android 10 (API 29) 及以上：通过 MediaStore 往 Pictures 公共集合写「自己创建的媒体」，
 *     属于分区存储允许的操作，**不需要任何运行时权限**，系统不会弹框，系统管家里也看不到
 *     任何存储权限条目 —— 这是正常的，不是配置漏了。
 *   - Android 9 (API 28) 及以下：只能直接写公共外部存储，必须先拿到
 *     WRITE_EXTERNAL_STORAGE（Capacitor 权限流程会弹系统授权框）。
 *
 * 因此 saveImage() 在 API 29+ 是「零权限」路径。为了不出现「提示保存成功但相册里找不到」，
 * 每次写入后都会：
 *   1. 关闭流 → 清 IS_PENDING → **回读 MediaStore 的 SIZE 校验**，对不上就报错；
 *   2. 把真实的 location / uri / size 返回给前端显示；
 *   3. 额外把文件写一份到应用自己的外部目录（App 专属目录，必然可写），作为兜底凭据。
 */
@CapacitorPlugin(
    name = "SaveToGallery",
    permissions = {
        @Permission(
            alias = "storage",
            strings = { android.Manifest.permission.WRITE_EXTERNAL_STORAGE }
        )
    }
)
public class SaveToGalleryPlugin extends Plugin {

    /** 相册里显示的子目录名（Android 10+ 由 MediaStore 创建） */
    private static final String ALBUM_DIR = "贴贴岛";

    /** 应用专属兜底目录名（getExternalFilesDir 下），不需要任何权限 */
    private static final String FALLBACK_DIR = "exports";

    @PluginMethod
    public void saveImage(PluginCall call) {
        if (needsLegacyStoragePermission()
            && getPermissionState("storage") != PermissionState.GRANTED) {
            requestPermissionForAlias("storage", call, "storagePermissionCallback");
            return;
        }
        performSave(call);
    }

    @PermissionCallback
    private void storagePermissionCallback(PluginCall call) {
        if (getPermissionState("storage") == PermissionState.GRANTED) {
            performSave(call);
        } else {
            reject(call, "PERMISSION_DENIED", "未获得存储权限，无法保存到相册");
        }
    }

    /**
     * 环境自检：给前端在启动时调用，用来确认「本设备到底需不需要权限、插件是否就绪」。
     * 只读，不写任何文件。
     */
    @PluginMethod
    public void probe(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("ok", true);
            result.put("sdkInt", Build.VERSION.SDK_INT);
            result.put("needsStoragePermission", needsLegacyStoragePermission());
            result.put(
                "permissionState",
                getPermissionState("storage").toString()
            );
            result.put("albumDir", "Pictures/" + ALBUM_DIR);
            result.put("canWriteSilently", !needsLegacyStoragePermission());
            call.resolve(result);
        } catch (Exception e) {
            reject(call, "PROBE_FAILED", "环境自检失败: " + e);
        }
    }

    /** 打开相册里刚保存的那张图（用于「保存后一键查看」）。 */
    @PluginMethod
    public void openImage(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.length() == 0) {
            reject(call, "BAD_ARGS", "缺少 uri");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(uriString), mimeTypeFor(uriString));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            JSObject result = new JSObject();
            result.put("ok", true);
            result.put("opened", true);
            call.resolve(result);
        } catch (Exception e) {
            reject(call, "OPEN_FAILED", "没有可打开该图片的应用: " + e.getMessage());
        }
    }

    private void performSave(PluginCall call) {
        String base64 = call.getString("base64");
        String filename = call.getString("filename", "tietiedao.png");
        if (base64 == null || base64.length() == 0) {
            reject(call, "EMPTY_DATA", "base64 数据为空");
            return;
        }
        if (filename.contains("/") || filename.contains("\\")) {
            filename = filename.replaceAll("[/\\\\]", "_");
        }

        final byte[] bytes;
        try {
            bytes = Base64.decode(stripDataUrl(base64), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            reject(call, "BAD_BASE64", "base64 解码失败: " + e.getMessage());
            return;
        }

        JSObject result = new JSObject();
        result.put("ok", true);
        result.put("filename", filename);
        result.put("sdkInt", Build.VERSION.SDK_INT);
        result.put("bytes", bytes.length);

        try {
            File backing = writeToAppDir(filename, bytes);
            result.put("appCopyPath", backing.getAbsolutePath());
        } catch (IOException e) {
            // 兜底副本失败不影响主流程，只记录
            result.put("appCopyPath", "");
            result.put("appCopyError", String.valueOf(e.getMessage()));
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri uri = insertWithMediaStore(filename, bytes);
                verifyMediaStoreEntry(uri, bytes.length);
                result.put("uri", uri.toString());
                result.put("location", "Pictures/" + ALBUM_DIR + "/" + filename);
                result.put("verified", true);
                result.put("via", "MediaStore");
            } else {
                File file = writeLegacyFile(filename, bytes);
                result.put("uri", Uri.fromFile(file).toString());
                result.put("location", file.getAbsolutePath());
                result.put("verified", file.exists() && file.length() == bytes.length);
                result.put("via", "legacyExternalStorage");
            }
            call.resolve(result);
        } catch (Exception e) {
            reject(call, "SAVE_FAILED", describe(e));
        }
    }

    private boolean needsLegacyStoragePermission() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    private String stripDataUrl(String data) {
        int comma = data.indexOf(',');
        return comma >= 0 ? data.substring(comma + 1) : data;
    }

    private String mimeTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    /** 把异常类型也带上，方便定位到底是哪一步失败。 */
    private String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private void reject(PluginCall call, String code, String message) {
        // resolve 而不是 reject：让前端拿到结构化结果，能显示真实原因，
        // 而不是只剩一句笼统的失败提示。
        JSObject result = new JSObject();
        result.put("ok", false);
        result.put("code", code);
        result.put("error", message);
        call.resolve(result);
    }

    /** Android 10+：写进 Pictures 公共集合，不需要运行时权限。 */
    private Uri insertWithMediaStore(String filename, byte[] bytes) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeTypeFor(filename));
        values.put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/" + ALBUM_DIR
        );
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore.insert 返回 null（系统拒绝创建条目）");

        OutputStream out = null;
        try {
            out = resolver.openOutputStream(uri);
            if (out == null) throw new IOException("openOutputStream 返回 null");
            out.write(bytes);
            out.flush();
        } finally {
            if (out != null) out.close();
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    /**
     * 写完之后按 URI 回读一次：确认 MediaStore 里这条记录真的存在、SIZE 与实际字节数一致。
     * 某些 ROM 上 insert + 写入会静默失败（返回了 URI 但文件不存在），这一步能把那种情况暴露出来。
     */
    private void verifyMediaStoreEntry(Uri uri, int expectedBytes) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        String[] projection = {
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        };
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, projection, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("回读失败：MediaStore 里查不到刚写入的条目 " + uri);
            }
            long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE));
            String name = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            );
            String path = null;
            int pathIdx = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
            if (pathIdx >= 0) path = cursor.getString(pathIdx);
            if (size != expectedBytes) {
                throw new IOException(
                    "回读校验不一致：MediaStore 记录 size=" + size
                        + "，实际写入 " + expectedBytes + " 字节（name=" + name + "）"
                );
            }
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Android 9 及以下：直接写公共 Pictures 目录（需 WRITE_EXTERNAL_STORAGE）。 */
    private File writeLegacyFile(String filename, byte[] bytes) throws IOException {
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_DIR
        );
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir.getAbsolutePath());
        }
        File file = new File(dir, filename);
        writeBytes(file, bytes);
        android.media.MediaScannerConnection.scanFile(
            getContext(),
            new String[] { file.getAbsolutePath() },
            new String[] { mimeTypeFor(filename) },
            null
        );
        return file;
    }

    /**
     * 兜底副本：写进应用专属外部目录 getExternalFilesDir(Pictures)，
     * 这个目录一定可写、不需要任何权限，用来证明「图确实生成并落盘了」。
     * 它不会出现在相册里，仅作诊断/兜底。
     */
    private File writeToAppDir(String filename, byte[] bytes) throws IOException {
        File base = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) base = getContext().getFilesDir();
        File dir = new File(base, FALLBACK_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建兜底目录 " + dir.getAbsolutePath());
        }
        File file = new File(dir, filename);
        writeBytes(file, bytes);
        return file;
    }

    private void writeBytes(File file, byte[] bytes) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(bytes);
            out.flush();
        } finally {
            if (out != null) out.close();
        }
    }
}
