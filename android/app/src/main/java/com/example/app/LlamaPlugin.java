package com.example.app;

import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Environment;
import android.provider.Settings;

@CapacitorPlugin(name = "Llama")
public class LlamaPlugin extends Plugin {
    private boolean isGenerating = false;

    // ════════ 核心修复：用于暂存被截断的半个中文字符 ════════
    private byte[] leftoverBytes = new byte[0];

    static {
        System.loadLibrary("clover-bridge");
    }

    public native String nativeLoadModel(String path);
    public native byte[] nativeGenerate(String prompt, int maxTokens, String systemPrompt, int contextSize, int threads);
    public native void nativeStop();

    @PluginMethod
    public void stop(PluginCall call) {
        nativeStop();
        isGenerating = false;
        leftoverBytes = new byte[0]; // 停止时清空残留
        call.resolve();
    }

    // 【核心修复版】智能 UTF-8 字节缝合器
    public void onTokenGenerated(byte[] newBytes) {
        try {
            // 1. 拼接上次残留的字节
            byte[] combined = new byte[leftoverBytes.length + newBytes.length];
            System.arraycopy(leftoverBytes, 0, combined, 0, leftoverBytes.length);
            System.arraycopy(newBytes, 0, combined, leftoverBytes.length, newBytes.length);

            // 2. 寻找完整的 UTF-8 边界
            int validLength = combined.length;
            int i = combined.length - 1;

            // 最多往回找 4 个字节 (UTF-8 的最大长度)
            while (i >= 0 && i > combined.length - 4) {
                byte b = combined[i];
                if ((b & 0x80) == 0) {
                    // 单字节 ASCII，边界完整
                    break;
                } else if ((b & 0xC0) == 0xC0) {
                    // 找到多字节字符的头部字节
                    int expectedLen = 0;
                    if ((b & 0xE0) == 0xC0) expectedLen = 2; // 110xxxxx
                    else if ((b & 0xF0) == 0xE0) expectedLen = 3; // 1110xxxx (中文字符常用)
                    else if ((b & 0xF8) == 0xF0) expectedLen = 4; // 11110xxx (Emoji 常用)

                    if (combined.length - i < expectedLen) {
                        // 如果尾部的字节数不够拼出这个字符，说明被截断了
                        validLength = i;
                    }
                    break;
                }
                i--;
            }

            // 3. 如果连一个完整的字符都拼不出来，全部存入残留，等待下个包
            if (validLength == 0) {
                leftoverBytes = combined;
                return;
            }

            // 4. 提取出完整的字节进行解码
            byte[] validBytes = new byte[validLength];
            System.arraycopy(combined, 0, validBytes, 0, validLength);

            // 5. 将剩下不完整的字节保存，留给下次回调
            leftoverBytes = new byte[combined.length - validLength];
            System.arraycopy(combined, validLength, leftoverBytes, 0, leftoverBytes.length);

            // 6. 转换为 String，此时发送给前端的文本绝对不会出现
            String text = new String(validBytes, StandardCharsets.UTF_8);

            if (!text.isEmpty()) {
                JSObject ret = new JSObject();
                ret.put("text", text);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        notifyListeners("onToken", ret);
                    });
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
    }

    @PluginMethod
    public void load(PluginCall call) {
        new Thread(() -> {
            android.os.ParcelFileDescriptor pfd = null;
            try {
                String path = call.getString("path");
                String realPathToLoad = path;

                if (path != null && path.startsWith("content://")) {
                    Uri uri = Uri.parse(path);
                    pfd = getContext().getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd != null) {
                        int fd = pfd.getFd();
                        realPathToLoad = "/proc/self/fd/" + fd;
                    } else {
                        call.reject("无法打开外部文件描述符");
                        return;
                    }
                } else {
                    File f = new File(path);
                    if (!f.exists()) {
                        call.reject("实体文件不存在，可能已被删除");
                        return;
                    }
                }

                String result = nativeLoadModel(realPathToLoad);

                if (pfd != null) {
                    pfd.close();
                }

                if (result.startsWith("Error")) {
                    call.reject(result);
                } else {
                    JSObject ret = new JSObject();
                    ret.put("status", result);
                    call.resolve(ret);
                }
            } catch (Exception e) {
                call.reject("载入失败: " + e.getMessage());
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    @PluginMethod
    public void checkStoragePermission(PluginCall call) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent);

                JSObject ret = new JSObject();
                ret.put("granted", false);
                call.resolve(ret);
                return;
            }
        }
        JSObject ret = new JSObject();
        ret.put("granted", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String path = call.getString("path");
        if (path != null) {
            if (path.startsWith("content://")) {
                try {
                    getContext().getContentResolver().releasePersistableUriPermission(Uri.parse(path), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            } else {
                File file = new File(path);
                if (file.exists()) {
                    file.delete();
                }
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void generate(PluginCall call) {
        if (isGenerating) {
            call.reject("AI 正在加载中...");
            return;
        }

        // ════════ 核心修改：开始新对话前，确保清空上一次的残余字节 ════════
        leftoverBytes = new byte[0];

        String prompt = call.getString("prompt");
        Integer maxTokens = call.getInt("maxTokens", 256);
        String systemPrompt = call.getString("systemPrompt", "");
        Integer contextSize = call.getInt("contextSize", 1024);
        Integer threads = call.getInt("threads", 4);
        isGenerating = true;

        new Thread(() -> {
            try {
                byte[] bytes = nativeGenerate(prompt, maxTokens, systemPrompt, contextSize, threads);

                if (bytes == null || bytes.length == 0) {
                    call.reject("AI 生成了空结果或崩潰");
                    return;
                }

                String response = new String(bytes, StandardCharsets.UTF_8);
                String totalTime = "0.0"; // 新增总耗时
                String pSpeed = "0.0";
                String gSpeed = "0.0";

                String tokenMarker = "[CLOVER_STATS|";
                int markerIdx = response.lastIndexOf(tokenMarker);
                if (markerIdx != -1) {
                    int endIdx = response.lastIndexOf("]");
                    if (endIdx > markerIdx) {
                        String statsStr = response.substring(markerIdx + tokenMarker.length(), endIdx);
                        String[] parts = statsStr.split("\\|");
                        if (parts.length == 3) { // 变成 3 个参数
                            totalTime = parts[0];
                            pSpeed = parts[1];
                            gSpeed = parts[2];
                        }
                        response = response.substring(0, markerIdx);
                    }
                }

                JSObject ret = new JSObject();
                ret.put("content", response);
                ret.put("totalTime", totalTime); // 传给 JS
                ret.put("promptSpeed", pSpeed);
                ret.put("genSpeed", gSpeed);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("發生異常: " + e.getMessage());
            } finally {
                isGenerating = false;
            }
        }).start();
    }

    @PluginMethod
    public void pickModel(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(call, intent, "pickModelResult");
    }

    @ActivityCallback
    private void pickModelResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result.getResultCode() == getActivity().RESULT_OK) {
            Intent data = result.getData();
            if (data != null) {
                Uri uri = data.getData();
                try {
                    String originalName = "未知文件.gguf";
                    long sizeBytes = 0;
                    Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameIndex != -1) originalName = cursor.getString(nameIndex);
                        if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex);
                        cursor.close();
                    }
                    String sizeStr = String.format("%.2f GB", sizeBytes / 1073741824.0);
                    String mode = call.getString("mode", "link");

                    if ("copy".equals(mode)) {
                        final String fOriginalName = originalName;
                        final String fSizeStr = sizeStr;
                        new Thread(() -> {
                            try {
                                InputStream is = getContext().getContentResolver().openInputStream(uri);
                                File outFile = new File(getContext().getFilesDir(), fOriginalName);
                                FileOutputStream fos = new FileOutputStream(outFile);
                                byte[] buffer = new byte[8192];
                                int length;
                                while ((length = is.read(buffer)) > 0) {
                                    fos.write(buffer, 0, length);
                                }
                                fos.close();
                                is.close();

                                JSObject ret = new JSObject();
                                ret.put("path", outFile.getAbsolutePath());
                                ret.put("size", fSizeStr);
                                ret.put("originalName", fOriginalName);
                                ret.put("storageType", "internal");
                                call.resolve(ret);
                            } catch (Exception e) {
                                call.reject("复制文件失败: " + e.getMessage());
                            }
                        }).start();

                    } else {
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);

                        JSObject ret = new JSObject();
                        ret.put("path", uri.toString());
                        ret.put("size", sizeStr);
                        ret.put("originalName", originalName);
                        ret.put("storageType", "external");
                        call.resolve(ret);
                    }
                } catch (Exception e) {
                    call.reject("处理文件失败: " + e.getMessage());
                }
            } else {
                call.reject("未选择文件");
            }
        } else {
            call.reject("取消选择");
        }
    }
}
