package com.okhttplib;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Message;
import android.support.annotation.IntDef;
import android.text.TextUtils;
import android.util.Log;

import com.okhttplib.annotation.CacheLevel;
import com.okhttplib.annotation.CacheType;
import com.okhttplib.bean.CallbackMessage;
import com.okhttplib.bean.UploadFileInfo;
import com.okhttplib.bean.UploadMessage;
import com.okhttplib.callback.BaseActivityLifecycleCallbacks;
import com.okhttplib.callback.CallbackOk;
import com.okhttplib.callback.ProgressCallback;
import com.okhttplib.handler.OkMainHandler;
import com.okhttplib.progress.ProgressRequestBody;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.okhttplib.annotation.CacheLevel.FIRST_LEVEL;
import static com.okhttplib.annotation.CacheLevel.FOURTH_LEVEL;
import static com.okhttplib.annotation.CacheLevel.SECOND_LEVEL;
import static com.okhttplib.annotation.CacheLevel.THIRD_LEVEL;
import static com.okhttplib.annotation.CacheType.CACHE_THEN_NETWORK;
import static com.okhttplib.annotation.CacheType.FORCE_CACHE;
import static com.okhttplib.annotation.CacheType.FORCE_NETWORK;
import static com.okhttplib.annotation.CacheType.NETWORK_THEN_CACHE;


public class OkHttpUtil {

    private final String TAG = getClass().getSimpleName();
    private static Application application;
    private static OkHttpClient httpClient;
    private static Builder builderGlobal;
    private int cacheLevel;
    private int cacheType;
    private int cacheSurvivalTime;
    private Class<?> tag;
    private boolean showHttpLog;
    private ExecutorService executorService;

    /**
     * 请求时间戳：区别每次请求标识
     */
    private long timeStamp;

    /**
     * 初始化：请在Application中调用
     * @param context 上下文
     */
    public static Builder init(Application context){
        application = context;
        application.registerActivityLifecycleCallbacks(new BaseActivityLifecycleCallbacks());
        return BuilderGlobal();
    }

    public static OkHttpUtil getDefault(){
        return new Builder(false).build();
    }

    /**
     * 获取默认请求配置
     * @param object 请求标识
     * @return OkHttpUtil
     */
    public static OkHttpUtil getDefault(Object object){
        return new Builder(false).build(object);
    }

    /**
     * 同步Post请求
     * @param info 请求信息体
     * @return HttpInfo
     */
    public HttpInfo doPostSync(HttpInfo info){
        return doRequestSync(info, POST, null);
    }

    /**
     * 同步Get请求
     * @param info 请求信息体
     * @return HttpInfo
     */
    public HttpInfo doGetSync(HttpInfo info){
        return doRequestSync(info, GET, null);
    }

    /**
     * 异步Post请求
     * @param info 请求信息体
     * @param callback 回调接口
     */
    public void doPostAsync(HttpInfo info, CallbackOk callback){
        doRequestAsync(info, POST, callback, null);
    }

    /**
     * 异步Get请求
     * @param info 请求信息体
     * @param callback 回调接口
     */
    public void doGetAsync(HttpInfo info, CallbackOk callback){
        doRequestAsync(info, GET, callback, null);
    }

    /**
     * 异步上传文件
     * @param info 请求信息体
     */
    public void doUploadFileAsync(final HttpInfo info){
        List<UploadFileInfo> uploadFiles = info.getUploadFile();
        for(final UploadFileInfo fileInfo : uploadFiles){
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    uploadFile(fileInfo, info);
                }
            });
        }
    }

    /**
     * 同步上传文件
     * @param info 请求信息体
     */
    public void doUploadFileSync(final HttpInfo info){
        List<UploadFileInfo> uploadFiles = info.getUploadFile();
        for(final UploadFileInfo fileInfo : uploadFiles){
            uploadFile(fileInfo, info);
        }
    }

    private void uploadFile(UploadFileInfo fileInfo, HttpInfo info){
        String filePath = fileInfo.getFilePathWithName();
        String interfaceParamName = fileInfo.getInterfaceParamName();
        String url = fileInfo.getUrl();
        url = TextUtils.isEmpty(url) ? info.getUrl() : url;
        if(TextUtils.isEmpty(url)){
            showLog("文件上传接口地址不能为空["+filePath+"]");
            return ;
        }
        ProgressCallback progressCallback = fileInfo.getProgressCallback();
        File file = new File(filePath);
        MultipartBody.Builder mBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        StringBuilder log = new StringBuilder("PostParams: ");
        log.append(interfaceParamName+"="+filePath);
        String logInfo;
        if(null != info.getParams() && !info.getParams().isEmpty()){
            for (String key : info.getParams().keySet()) {
                mBuilder.addFormDataPart(key, info.getParams().get(key));
                logInfo = key+" ="+info.getParams().get(key)+", ";
                log.append(logInfo);
            }
        }
        showLog(log.toString());
        mBuilder.addFormDataPart(interfaceParamName,
                file.getName(),
                RequestBody.create(fetchFileMediaType(filePath), file));
        RequestBody requestBody = mBuilder.build();
        final Request request = new Request
                .Builder()
                .url(url)
                .post(new ProgressRequestBody(requestBody,progressCallback))
                .build();
        doRequestSync(info,POST,request);
        Message msg = new UploadMessage(
                OkMainHandler.RESPONSE_UPLOAD_CALLBACK,
                filePath,
                info,
                progressCallback)
                .build();
        OkMainHandler.getInstance().sendMessage(msg);
    }

    private MediaType fetchFileMediaType(String filePath){
        if(!TextUtils.isEmpty(filePath) && filePath.contains(".")){
            String extension = filePath.substring(filePath.lastIndexOf(".") + 1);
            if("png".equals(extension)){
                extension = "image/png";
            }else if("jpg".equals(extension)){
                extension = "image/jpg";
            }else if("jpeg".equals(extension)){
                extension = "image/jpeg";
            }else if("gif".equals(extension)){
                extension = "image/gif";
            }else if("bmp".equals(extension)){
                extension = "image/bmp";
            }else if("tiff".equals(extension)){
                extension = "image/tiff";
            }else{
                return null;
            }
            return MediaType.parse(extension);
        }
        return null;
    }


    /**
     * 下载文件
     * @param info 请求信息体
     * @param callback 回调接口
     */
    public void doDownloadFile(final HttpInfo info, final CallbackOk callback){
        if(TextUtils.isEmpty(info.getUrl())){
            showLog("下载文件失败：文件下载地址不能为空！");
            return ;
        }



    }

    /**
     * 同步请求
     * @param info 请求信息体
     * @param method 请求方法
     * @return HttpInfo
     */
    private HttpInfo doRequestSync(HttpInfo info, @Method int method, Request request){
        Call call = null;
        try {
            String url = info.getUrl();
            if(TextUtils.isEmpty(url)){
                return retInfo(info,info.CheckURL);
            }
            call = httpClient.newCall(request == null ? fetchRequest(info,method) : request);
            BaseActivityLifecycleCallbacks.putCall(tag,info,call);
            Response res = call.execute();
            return dealResponse(info, res, call);
        } catch (IllegalArgumentException e){
            return retInfo(info,info.ProtocolException);
        } catch (SocketTimeoutException e){
            if(null != e.getMessage()){
                if(e.getMessage().contains("failed to connect to"))
                    return retInfo(info,info.ConnectionTimeOut);
                if(e.getMessage().equals("timeout"))
                    return retInfo(info,info.WriteAndReadTimeOut);
            }
            return retInfo(info,info.WriteAndReadTimeOut);
        } catch (UnknownHostException e) {
            return retInfo(info,info.CheckNet);
        } catch (Exception e) {
            return retInfo(info,info.NoResult);
        }finally {
            BaseActivityLifecycleCallbacks.cancelCall(tag,info,call);
        }
    }

    /**
     * 异步请求
     * @param info 请求信息体
     * @param method 请求方法
     * @param callback 回调接口
     */
    private void doRequestAsync(final HttpInfo info, @Method int method, final CallbackOk callback, Request request){
        if(null == callback)
            throw new NullPointerException("CallbackOk is null that not allowed");
        Call call = httpClient.newCall(request == null ? fetchRequest(info,method) : request);
        BaseActivityLifecycleCallbacks.putCall(tag,info,call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                showLog(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response res) throws IOException {
                //主线程回调
                Message msg =  new CallbackMessage(OkMainHandler.RESPONSE_CALLBACK,
                        callback,
                        dealResponse(info,res,call))
                        .build();
                OkMainHandler.getInstance().sendMessage(msg);
                BaseActivityLifecycleCallbacks.cancelCall(tag,info,call);
            }
        });
    }

    private HttpInfo dealResponse(HttpInfo info, Response res, Call call){
        try {
            if(null != res){
                if(res.isSuccessful() && null != res.body()){
                    return retInfo(info,info.SUCCESS,res.body().string());
                }else{
                    showLog("HttpStatus: "+res.code());
                    if(res.code() == 404)//请求页面路径错误
                        return retInfo(info,info.CheckURL);
                    if(res.code() == 500)//服务器内部错误
                        return retInfo(info,info.NoResult);
                    if(res.code() == 502)//错误网关
                        return retInfo(info,info.CheckNet);
                    if(res.code() == 504)//网关超时
                        return retInfo(info,info.CheckNet);
                }
            }
            return retInfo(info,info.CheckURL);
        } catch (Exception e) {
            e.printStackTrace();
            return retInfo(info,info.NoResult);
        } finally {
            if(null != res)
                res.close();
        }
    }

    private Request fetchRequest(HttpInfo info, @Method int method){
        Request request;
        if(method == POST){
            FormBody.Builder builder = new FormBody.Builder();
            if(null != info.getParams() && !info.getParams().isEmpty()){
                StringBuilder log = new StringBuilder("PostParams: ");
                String logInfo;
                for (String key : info.getParams().keySet()) {
                    builder.add(key, info.getParams().get(key));
                    logInfo = key+" ="+info.getParams().get(key)+", ";
                    log.append(logInfo);
                }
                showLog(log.toString());
            }
            if(null != info.getUploadFile() && !info.getUploadFile().isEmpty()){

            }
            request = new Request.Builder()
                    .url(info.getUrl())
                    .post(builder.build())
                    .build();
        }else{
            StringBuilder params = new StringBuilder();
            params.append(info.getUrl());
            if(null != info.getParams() && !info.getParams().isEmpty()){
                String logInfo;
                for (String name : info.getParams().keySet()) {
                    logInfo = "&" + name + "=" + info.getParams().get(name);
                    params.append(logInfo);
                }
            }
            request = new Request.Builder()
                    .url(params.toString())
                    .get()
                    .build();
        }
        return request;
    }

    private HttpInfo retInfo(HttpInfo info, int code){
        retInfo(info,code,null);
        return info;
    }

    private HttpInfo retInfo(HttpInfo info, int code, String resDetail){
        info.packInfo(code,resDetail);
        showLog("Response: "+info.getRetDetail());
        return info;
    }

    /**
     * 网络请求拦截器
     */
    private Interceptor CACHE_CONTROL_NETWORK_INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response.Builder resBuilder = chain.proceed(chain.request()).newBuilder();
            resBuilder.removeHeader("Pragma")
                    .header("Cache-Control", String.format("max-age=%d", cacheSurvivalTime));
            return resBuilder.build();
        }
    };

    /**
     * 缓存应用拦截器
     */
    private Interceptor CACHE_CONTROL_INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            switch (cacheType){
                case FORCE_CACHE:
                    request = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build();
                    break;
                case FORCE_NETWORK:
                    request = request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build();
                    break;
                case NETWORK_THEN_CACHE:
                    if(!isNetworkAvailable(application)){
                        request = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build();
                    }else {
                        request = request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build();
                    }
                    break;
                case CACHE_THEN_NETWORK:
                    if(!isNetworkAvailable(application)){
                        request = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build();
                    }
                    break;
            }
            return chain.proceed(request);
        }
    };

    private boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo net = cm.getActiveNetworkInfo();
        if (net != null && net.getState() == NetworkInfo.State.CONNECTED) {
            return true;
        }
        return false;
    }

    /**
     * 日志拦截器
     */
    private Interceptor LOG_INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            long startTime = System.currentTimeMillis();
            showLog(String.format("%s-URL: %s %n",chain.request().method(),
                    chain.request().url()));
            Response res = chain.proceed(chain.request());
            long endTime = System.currentTimeMillis();
            showLog(String.format("CostTime: %.1fs", (endTime-startTime) / 1000.0));
            return res;
        }
    };

    private OkHttpUtil(Builder builder) {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(builder.connectTimeout, TimeUnit.SECONDS)
                .readTimeout(builder.readTimeout, TimeUnit.SECONDS)
                .writeTimeout(builder.writeTimeout, TimeUnit.SECONDS)
                .cache(new Cache(builder.cachedDir,builder.maxCacheSize))
                .retryOnConnectionFailure(builder.retryOnConnectionFailure)
                .addInterceptor(CACHE_CONTROL_INTERCEPTOR)
                .addNetworkInterceptor(CACHE_CONTROL_NETWORK_INTERCEPTOR);
        if(null != builder.networkInterceptors && !builder.networkInterceptors.isEmpty())
            clientBuilder.networkInterceptors().addAll(builder.networkInterceptors);
        if(null != builder.interceptors && !builder.interceptors.isEmpty())
            clientBuilder.interceptors().addAll(builder.interceptors);
        clientBuilder.addInterceptor(LOG_INTERCEPTOR);
        setSslSocketFactory(clientBuilder);
        httpClient = clientBuilder.build();
        timeStamp = System.currentTimeMillis();
        final int deviation = 5;
        this.cacheLevel = builder.cacheLevel;
        this.cacheType = builder.cacheType;
        this.cacheSurvivalTime = builder.cacheSurvivalTime;
        this.tag = builder.tag;
        this.showHttpLog = builder.showHttpLog;
        if(this.cacheSurvivalTime == 0){
            switch (this.cacheLevel){
                case FIRST_LEVEL:
                    this.cacheSurvivalTime = 0;
                    break;
                case SECOND_LEVEL:
                    this.cacheSurvivalTime = 15 + deviation;
                    break;
                case THIRD_LEVEL:
                    this.cacheSurvivalTime = 30 + deviation;
                    break;
                case FOURTH_LEVEL:
                    this.cacheSurvivalTime = 60 + deviation;
                    break;
            }
        }
        if(this.cacheSurvivalTime > 0)
            cacheType = CACHE_THEN_NETWORK;
        BaseActivityLifecycleCallbacks.setShowLifecycleLog(builder.showLifecycleLog);
        executorService = Executors.newCachedThreadPool();
    }

    /**
     * 设置HTTPS认证
     */
    private void setSslSocketFactory(OkHttpClient.Builder clientBuilder){
        clientBuilder.hostnameVerifier(DO_NOT_VERIFY);
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            X509TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            sc.init(null,new TrustManager[]{trustManager}, new SecureRandom());
            clientBuilder.sslSocketFactory(sc.getSocketFactory(),trustManager);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *主机名验证
     */
    private final HostnameVerifier DO_NOT_VERIFY = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    public static Builder Builder() {
        return new Builder(false);
    }

    private static Builder BuilderGlobal() {
        return new Builder(true);
    }

    public static final class Builder {

        private int maxCacheSize;//缓存大小
        private File cachedDir;//缓存目录
        private int connectTimeout;//连接超时
        private int readTimeout;//读超时
        private int writeTimeout;//写超时
        private boolean retryOnConnectionFailure;//失败重新连接
        private List<Interceptor> networkInterceptors;//网络拦截器
        private List<Interceptor> interceptors;//应用拦截器
        private int cacheSurvivalTime;//缓存存活时间（秒）
        private int cacheType;//缓存类型
        private int cacheLevel;//缓存级别
        private boolean isGlobalConfig;//是否全局配置
        private boolean showHttpLog;//是否显示Http请求日志
        private boolean showLifecycleLog;//是否显示ActivityLifecycle日志
        private Class<?> tag;

        public Builder() {
        }

        public Builder(boolean isGlobal) {
            isGlobalConfig = isGlobal;
            //系统默认配置
            initDefaultConfig();
            if(!isGlobal){
                if(null != builderGlobal){
                    //全局自定义配置
                    initGlobalConfig(builderGlobal);
                }
            }
        }

        public OkHttpUtil build(){
            return build(null);
        }

        public OkHttpUtil build(Object object) {
            if(isGlobalConfig){
                if(null == builderGlobal){
                    builderGlobal = this;
                }
            }
            if(null != object)
                setTag(object);
            return new OkHttpUtil(this);
        }

        /**
         * 系统默认配置
         */
        private void initDefaultConfig(){
            setMaxCacheSize(10 * 1024 * 1024);
            setCachedDir(application.getExternalCacheDir());
            setConnectTimeout(30);
            setReadTimeout(30);
            setWriteTimeout(30);
            setRetryOnConnectionFailure(true);
            setCacheSurvivalTime(0);
            setCacheType(NETWORK_THEN_CACHE);
            setCacheLevel(FIRST_LEVEL);
            setNetworkInterceptors(null);
            setInterceptors(null);
            setShowHttpLog(true);
            setShowLifecycleLog(false);
        }

        /**
         * 全局自定义配置
         * @param builder builder
         */
        private void initGlobalConfig(Builder builder){
            setMaxCacheSize(builder.maxCacheSize);
            setCachedDir(builder.cachedDir);
            setConnectTimeout(builder.connectTimeout);
            setReadTimeout(builder.readTimeout);
            setWriteTimeout(builder.writeTimeout);
            setRetryOnConnectionFailure(builder.retryOnConnectionFailure);
            setCacheSurvivalTime(builder.cacheSurvivalTime);
            setCacheType(builder.cacheType);
            setCacheLevel(builder.cacheLevel);
            setNetworkInterceptors(builder.networkInterceptors);
            setInterceptors(builder.interceptors);
            setShowHttpLog(builder.showHttpLog);
            setShowLifecycleLog(builder.showLifecycleLog);
        }

        public Builder setMaxCacheSize(int maxCacheSize) {
            this.maxCacheSize = maxCacheSize;
            return this;
        }

        public Builder setCachedDir(File cachedDir) {
            if(null != cachedDir)
                this.cachedDir = cachedDir;
            return this;
        }

        public Builder setConnectTimeout(int connectTimeout) {
            if(connectTimeout <= 0)
                throw new IllegalArgumentException("connectTimeout must be > 0");
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setReadTimeout(int readTimeout) {
            if(readTimeout <= 0)
                throw new IllegalArgumentException("readTimeout must be > 0");
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder setWriteTimeout(int writeTimeout) {
            if(writeTimeout <= 0)
                throw new IllegalArgumentException("writeTimeout must be > 0");
            this.writeTimeout = writeTimeout;
            return this;
        }

        public Builder setRetryOnConnectionFailure(boolean retryOnConnectionFailure) {
            this.retryOnConnectionFailure = retryOnConnectionFailure;
            return this;
        }

        public Builder setNetworkInterceptors(List<Interceptor> networkInterceptors) {
            if(null != networkInterceptors)
                this.networkInterceptors = networkInterceptors;
            return this;
        }

        public Builder setInterceptors(List<Interceptor> interceptors) {
            if(null != interceptors)
                this.interceptors = interceptors;
            return this;
        }

        public Builder setCacheSurvivalTime(int cacheSurvivalTime) {
            if(cacheSurvivalTime < 0)
                throw new IllegalArgumentException("cacheSurvivalTime must be >= 0");
            this.cacheSurvivalTime = cacheSurvivalTime;
            return this;
        }

        public Builder setCacheType(@CacheType int cacheType) {
            this.cacheType = cacheType;
            return this;
        }

        public Builder setCacheLevel(@CacheLevel int cacheLevel) {
            this.cacheLevel = cacheLevel;
            return this;
        }

        public Builder setShowHttpLog(boolean showHttpLog) {
            this.showHttpLog = showHttpLog;
            return this;
        }

        public Builder setShowLifecycleLog(boolean showLifecycleLog) {
            this.showLifecycleLog = showLifecycleLog;
            return this;
        }

        public Builder setTag(Object object) {
            if(object instanceof Activity){
                Activity activity = (Activity) object;
                this.tag = activity.getClass();
            }
            if(object instanceof android.support.v4.app.Fragment){
                android.support.v4.app.Fragment fragment = (android.support.v4.app.Fragment) object;
                this.tag = fragment.getActivity().getClass();
            }
            if(object instanceof android.app.Fragment){
                android.app.Fragment fragment = (android.app.Fragment) object;
                this.tag = fragment.getActivity().getClass();
            }
            return this;
        }
    }

    /**
     * 打印日志
     * @param msg 日志信息
     */
    private void showLog(String msg){
        if(this.showHttpLog)
            Log.d(TAG+"["+timeStamp+"]", msg);
    }

    /**
     * 请求方法
     */
    @IntDef({POST,GET})
    @Retention(RetentionPolicy.SOURCE)
    private  @interface Method{}
    private static final int POST = 1;
    private static final int GET = 2;


}
