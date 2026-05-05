package com.jrgames.audiorecorder.webdav;
import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
public class WebDavUploader {
    public interface UploadCallback {
        void onSuccess();
        void onFailure(String error);
    }
    private static OkHttpClient buildTrustAllClient() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), trustAll)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            return new OkHttpClient();
        }
    }
    private final OkHttpClient client = buildTrustAllClient();
    public void upload(WebDavConfig config, File file, UploadCallback callback) {
        new Thread(() -> {
            try {
                String server = config.server.trim();
                if (!server.endsWith("/")) server += "/";
                String dir = config.directory.trim();
                if (dir.startsWith("/")) dir = dir.substring(1);
                if (!dir.isEmpty() && !dir.endsWith("/")) dir += "/";
                String url = server + dir + file.getName();
                RequestBody body = RequestBody.create(file, MediaType.parse("audio/*"));
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .put(body);
                if (!config.username.isEmpty()) {
                    builder.header("Authorization", Credentials.basic(config.username, config.password));
                }
                try (Response response = client.newCall(builder.build()).execute()) {
                    if (response.isSuccessful()) {
                        callback.onSuccess();
                    } else {
                        callback.onFailure("HTTP " + response.code() + " " + response.message());
                    }
                }
            } catch (IOException e) {
                callback.onFailure(e.getMessage());
            }
        }).start();
    }
}