package top.sshh.bililiverecoder.util;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpClientUtil {
    private static OkHttpClient client;
    private static OkHttpClient clientAllowCookie;

    static {
        HttpsTrustManager.allowAllSSL();
        HttpsTrustManager manager = new HttpsTrustManager();
        client = new OkHttpClient().newBuilder()
                .sslSocketFactory(HttpsTrustManager.createSSLSocketFactory(),manager)
                .connectTimeout(150, TimeUnit.SECONDS)
                .readTimeout(150, TimeUnit.SECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .writeTimeout(150, TimeUnit.SECONDS)
                .build();

        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieJar cookieJar = new JavaNetCookieJar(cookieManager);
        clientAllowCookie = new OkHttpClient().newBuilder()
                .sslSocketFactory(HttpsTrustManager.createSSLSocketFactory(),manager)
                .connectTimeout(150, TimeUnit.SECONDS)
                .readTimeout(150, TimeUnit.SECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .writeTimeout(150, TimeUnit.SECONDS)
                .cookieJar(cookieJar)
                .build();
    }

    public static String post(String url, Map<String, String> headers, String json) {
        try {
            RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
            Request build = new Request.Builder()
                    .headers(Headers.of(headers))
                    .url(url)
                    .post(requestBody)
                    .build();
            return execute(build);
        } catch (IOException e) {
            log.error("[BLR] {}", LogKvs.event("Http.Request.Failed")
                    .add("method", "POST")
                    .addUrl("url", url)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new RuntimeException(e.getMessage());
        }
    }
    public static String post(String url, Map<String, String> headers, RequestBody requestBody) {
        try {
            Request build = new Request.Builder()
                    .headers(Headers.of(headers))
                    .url(url)
                    .post(requestBody)
                    .build();
            return execute(build);
        } catch (IOException e) {
            log.error("[BLR] {}", LogKvs.event("Http.Request.Failed")
                    .add("method", "POST")
                    .addUrl("url", url)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new RuntimeException(e.getMessage());
        }
    }

    public static String post(String url, Map<String, String> headers,
                              Map<String, String> formParams,
                              Boolean allowCookie) {
        FormBody.Builder builder = new FormBody.Builder();
        formParams.forEach(builder::add);
        RequestBody formBody = builder
                .build();
        Request build = new Request.Builder()
                .headers(Headers.of(headers))
                .url(url)
                .post(formBody)
                .build();
        OkHttpClient currentClient = allowCookie ? clientAllowCookie : client;
        try {
            return execute(currentClient, build);
        } catch (IOException e) {
            log.error("[BLR] {}", LogKvs.event("Http.Request.Failed")
                    .add("method", "POST")
                    .addUrl("url", url)
                    .add("allowCookie", allowCookie)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new RuntimeException(e.getMessage());
        }
    }

    private static String execute(Request request) throws IOException {
        return execute(client, request);
    }

    private static String execute(OkHttpClient client, Request request) throws IOException {
        if (top.sshh.bililiverecoder.service.RateLimiterService.getInstance() != null) {
            top.sshh.bililiverecoder.service.RateLimiterService.getInstance().getApiRateLimiter().acquire();
        }
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[BLR] {}", LogKvs.event("Http.Response.Error")
                        .add("code", response.code())
                        .addUrl("url", request.url()));
                // 如果是 502/504 等错误，通常返回的是 HTML，直接截断或返回 JSON 格式的错误信息
                if (response.code() >= 500 || (response.body() != null && response.body().contentType() != null && 
                    response.body().contentType().toString().contains("text/html"))) {
                    return "{\"code\": " + response.code() + ", \"message\": \"HTTP " + response.code() + " Error\", \"data\": null}";
                }
            }
            
            String string = response.body().string();
            
            // 简单的风控检测
            if (string.contains("\"code\":-412") || string.contains("\"code\": -412")) {
                log.warn("[BLR] {}", LogKvs.event("Http.RiskControl.Triggered")
                        .add("code", -412)
                        .addUrl("url", request.url()));
            }
            
            return string;
        }
    }

    public static String postJson(String url, Map<String, String> headers,
                                  Map<String, Object> formParams,
                                  Boolean allowCookie) {
        RequestBody requestBody = RequestBody.create(JSON.toJSONString(formParams), MediaType.get("application/json; charset=utf-8"));
        Request build = new Request.Builder()
                .headers(Headers.of(headers))
                .url(url)
                .post(requestBody)
                .build();
        OkHttpClient currentClient = allowCookie ? clientAllowCookie : client;
        try {
            return execute(currentClient, build);
        } catch (IOException e) {
            log.error("[BLR] {}", LogKvs.event("Http.Request.Failed")
                    .add("method", "POST")
                    .addUrl("url", url)
                    .add("allowCookie", allowCookie)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new RuntimeException(e.getMessage());
        }
    }

    public static String get(String url, Map<String, String> headers) {
        do {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .headers(Headers.of(headers))
                        .get()
                        .build();
                return execute(request);
            } catch (UnknownHostException e) {
                log.warn("[BLR] {}", LogKvs.event("Http.Dns.UnknownHost.Retry")
                        .addUrl("url", url)
                        .add("sleepMs", 5000)
                        .add("err", e.getMessage()));
                try {
                    Thread.sleep(5000L);
                } catch (Exception ignored) {
                }

            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("Http.Get.Failed")
                        .addUrl("url", url)
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
                throw new RuntimeException(e.getMessage());
            }
        } while (true);

    }

    public static String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return execute(request);
    }

    public static String upload(String url, Map<String,String> headers, Map<String, Object> params) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        params.forEach((k, v) -> {
            if (v instanceof String) {
                builder.addFormDataPart(k, (String) v);
            } else {
                builder.addFormDataPart(k, "file", (RequestBody)v);
            }
        });
        
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(builder.build())
                .build();
        return execute(request);
    }


    public static OkHttpClient getClient() {
        return client;
    }


}
