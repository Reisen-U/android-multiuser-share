package com.example.multiusershare;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class SharePage {
    private SharePage() { }

    static String load(Context context) {
        try (InputStream input = context.getAssets().open("share.html");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "<!doctype html><meta charset=\"utf-8\"><p>共享页面加载失败，请重新启动服务。</p>";
        }
    }
}
