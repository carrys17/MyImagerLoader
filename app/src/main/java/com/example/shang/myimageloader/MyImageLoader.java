package com.example.shang.myimageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shang on 2017/8/1.
 */

public class MyImageLoader {


    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final int MESSAGE_POST_RESULT = 1;
    private Context mContext;
    private LruCache<String ,Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private boolean mIsDiskLruCacheCreated = false;
    private ImageResizer mImageResizer = new ImageResizer();
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAX_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r,"MyImageLoader# "+mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAX_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            imageView.setImageBitmap(result.bitmap);////？？？？？
            String url = (String) imageView.getTag(TAG_KEY_URI);
            if (url.equals(result.url)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.i("xyz","set image bitmap ,but url has changed, ignored!");
            }
        }
    };


    private MyImageLoader(Context context){
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory()/1024); //转换为k
        int cacheSize = maxMemory / 8; // 缓存总容量大小
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
             // 用来计算单个对象的大小，源码默认返回1，一般需要重写该方法来计算对象的大小
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext,"bitmap");
        Log.i("xyz","磁盘缓存路径 = "+diskCacheDir);
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }
        if(getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){

            try {
                // 磁盘缓存的创建
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static MyImageLoader build(Context context){
        return new MyImageLoader(context);
    }

    // 内存缓存的添加
    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if(getBitmapFromMemoryCache(key)==null){
            mMemoryCache.put(key,bitmap);
        }
    }

    // 内存缓存的读取（通过key获得）
    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    // 异步加载
    public void bindBitmap(final String url,final ImageView imageView){
        bindBitmap(url,imageView,0,0);
    }

   public void bindBitmap(final String url, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI,url);
        Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if (bitmap!=null){
            imageView.setImageBitmap(bitmap);
            return;
        }
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(url, reqWidth, reqHeight);
                if (bitmap !=null){
                    LoaderResult result = new LoaderResult(imageView,url,bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    public Bitmap loadBitmap(String url,int reqWidth,int reqHeight){
        Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if (bitmap!=null){
            Log.i("xyz","loadBitmapFromMemoryCache,url: "+url);
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromDiskCache(url,reqWidth,reqHeight);
            if (bitmap!=null){
                Log.i("xyz","loadBitmapFromDiskCache,url :"+url);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(url,reqWidth,reqHeight);
            Log.i("xyz","loadBitmapFromHttp,url: "+url);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (bitmap == null && !mIsDiskLruCacheCreated){
            Log.i("xyz","encounter error,DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(url);
        }
        return bitmap;

    }


    // 内存缓存的读取(通过url的方式)
    private Bitmap loadBitmapFromMemoryCache(String url){
        final String key = hashKeyFromUrl(url);
        return getBitmapFromMemoryCache(key);
    }

    // 磁盘缓存的添加和读取
    private Bitmap loadBitmapFromHttp(String url,int reqWidth,int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network form UI Thread.");
        }
        if (mDiskLruCache == null){
            return null;
        }
        // 磁盘文件的添加   到mDiskLruCache.flush()完成
        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor!=null){
            OutputStream os = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url,os)){
                editor.commit();
            }else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url,reqWidth,reqHeight);
    }

    // 磁盘缓存的查找
    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()){
            Log.i("xyz","load bitmap from UI thread, it`s not recommended!");
        }
        if (mDiskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null){
            FileInputStream fis = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fd = fis.getFD();
            bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(fd,reqWidth,reqHeight);
            if (bitmap!=null){
                addBitmapToMemoryCache(key,bitmap); // 内存缓存的添加
            }
        }
        return bitmap;
    }

    // 利用文件输出流将网络下载图片写入到文件系统中,此时并没有写入，需要借助editor
    private boolean downloadUrlToStream(String urlString, OutputStream os) {
        HttpURLConnection connection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(connection.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(os,IO_BUFFER_SIZE);

            int b;
            while ((b = in.read())!= -1){
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            Log.i("xyz","download failed "+e);
        }finally {
            if (connection!=null){
                connection.disconnect();
            }
            if (in!=null){
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (out!=null){
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    // 直接从网络拉取图片
    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection connection = null;
        BufferedInputStream in = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(connection.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (connection!=null){
                connection.disconnect();
            }
            if (in!=null){
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return bitmap;
    }

    //  通过url的MD5值得到key
    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            // 将url通过MD5摘要成key值，有点像是加密的感觉。要通过MessageDigest类
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
            Log.i("xyz","url的MD5值转换 = "+cacheKey);
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }
    //二行制转字符串
    private String bytesToHexString(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte aDigest : digest) {
            String hex = Integer.toHexString(0xFF & aDigest);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    // 获取指定路径的可用剩余空间大小
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){ // 2.3
            return path.getUsableSpace();
        }
        final StatFs statFs = new StatFs(path.getPath());
        return statFs.getBlockSizeLong() * statFs.getAvailableBlocksLong();
    }

    // 获取磁盘缓存的路径，目录选择SD卡上的缓存目录 /sdcard/Android/data/package/cache
    // 好处是应用一旦被卸载，缓存目录也会被删除，不会造成垃圾剩余
    private File getDiskCacheDir(Context context, String uniqueName) {
        boolean isExternal = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (isExternal){
            cachePath = context.getExternalCacheDir().getPath();
        }else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath +File.separator+uniqueName);
    }

    private static class LoaderResult{
        public ImageView imageView;
        public String url;
        public Bitmap bitmap;
        public LoaderResult(ImageView imageView,String url,Bitmap bitmap){
            this.imageView = imageView;
            this.url = url;
            this.bitmap = bitmap;
        }
    }
}
