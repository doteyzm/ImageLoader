package com.test.imageloader.utils;

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

import com.test.imageloader.R;

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

public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private Context mContext;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private static final int TAG_KEY_URI = R.id.imageLoad;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;//设置默认的缓存大小为50M
    private boolean mIsDiskLruCacheCreated;//标识是否成功创建了存储卡缓存
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private ImageResize mImageResize;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();//当前设备的cpu核心数
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;//核心线程数
    private static final int MAXIUM_POOL_SIZE = CPU_COUNT + 2;//最大容量
    private static final long KEEP_ALIVE = 10L;//线程闲置超时时长
    private static final int MESSAGE_POST_RESULT = 1;//最大容量

    private static final ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndDecrement());
        }
    };
    private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), threadFactory
    );

    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            LoaderResult loaderResult = (LoaderResult) msg.obj;
            ImageView imageView = loaderResult.imageView;
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            //防止滑动时图片错位
            if (uri.equals(loaderResult.uri)) {
                imageView.setImageBitmap(loaderResult.bitmap);
            } else {
                Log.d(TAG, "imageView已改变不设置图片，忽略");
            }
        }
    };

    /**
     * 在初始化时创建内存和sd卡缓存
     *
     * @param context
     */
    public ImageLoader(Context context) {
        this.mContext = context.getApplicationContext();
        mImageResize = new ImageResize();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);//最大运行内存单位KB
        int cacheSize = maxMemory / 8;//内存缓存的大小
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                Log.d(TAG, "rowByte:" + bitmap.getRowBytes() + "height:" + bitmap.getHeight());
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };
        File diskCacheFile = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheFile.exists()) {
            diskCacheFile.mkdirs();
        }

        if (getUsedSpace(diskCacheFile) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheFile, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 异步方式加载图片
     */
    public void bindBitmap(final String url, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI, url);
        final Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(url, reqWidth, reqHeight);
                if (bitmap != null) {
                    LoaderResult loaderResult = new LoaderResult(imageView, url, bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, loaderResult).sendToTarget();
                }

            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    /**
     * 同步方式加载图片
     */
    public Bitmap loadBitmap(String url, int reqWidth, int reqHeight) {
        //现存内存取，没有从磁盘读取，没有再从网络拉取
        Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if (bitmap != null) {
            Log.d(TAG, "从内存中取的" + url);
            return bitmap;
        }
        bitmap = loadBitmapFromDiskCache(url, reqWidth, reqHeight);
        if (bitmap != null) {
            Log.d(TAG, "从SD中取的" + url);
            return bitmap;
        }
        bitmap = loadBitmapFromHttp(url, reqWidth, reqHeight);
        if (bitmap == null && !mIsDiskLruCacheCreated) {
            Log.d(TAG, "sd卡存储没有被创建");
            bitmap = downloadBitmapFromUrl(url);
        }

        return bitmap;
    }

    private Bitmap downloadBitmapFromUrl(String url) {
        Bitmap bitmap = null;
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream inputStream = null;
        try {
            URL reqUrl = new URL(url);
            httpURLConnection = (HttpURLConnection) reqUrl.openConnection();
            inputStream = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "从网络上下载图片失败");
        } finally {
            if (httpURLConnection != null)
                httpURLConnection.disconnect();
            try {
                if (inputStream != null)
                    inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return bitmap;

    }

    private Bitmap loadBitmapFromMemoryCache(String url) {
        String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromMemoryCache(key);
        return bitmap;
    }

    /**
     * 向内存中缓存图片
     *
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemoryCache(key) == null) {
            mMemoryCache.put(key, bitmap);
            Log.d(TAG, key + "测试：" + mMemoryCache);
        }
    }

    /**
     * 从内存中取图片
     *
     * @param key
     */
    private Bitmap getBitmapFromMemoryCache(String key) {
        Log.d(TAG, "测试1：" + mMemoryCache);
        return mMemoryCache.get(key);
    }

    /**
     * 从网络中下载图片并写入到sd卡
     */
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("不能在主线程中请求网络");
        }
        if (mDiskLruCache == null)
            return null;
        String key = hashKeyFromUrl(url);
        try {
            DiskLruCache.Editor editor = mDiskLruCache.edit(key);
            if (editor != null) {
                OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
                if (downloadUrlToStream(url, outputStream)) {
                    //下载成功
                    editor.commit();
                } else {
                    editor.abort();
                }
                mDiskLruCache.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    /**
     * 从SD卡中取图片
     */
    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "不推荐在主线程中加载图片");
        }
        if (mDiskLruCache == null)
            return null;
        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        try {
            DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
            if (snapshot != null) {
                FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                FileDescriptor fileDescriptor = fileInputStream.getFD();
                bitmap = mImageResize.decodeSampledBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
                if (bitmap != null) {
                    //将图片加载到内存缓存中
                    addBitmapToMemoryCache(key, bitmap);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return bitmap;
    }

    private boolean downloadUrlToStream(String url, OutputStream outputStream) {
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream in = null;
        BufferedOutputStream out = null;
        try {
            URL reqUrl = new URL(url);
            httpURLConnection = (HttpURLConnection) reqUrl.openConnection();
            in = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "下载图片失败");
        } finally {
            if (httpURLConnection != null)
                httpURLConnection.disconnect();
            try {
                if (out != null)
                    out.close();
                if (in != null)
                    in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    /**
     * 将图片的url经过md5加密转换为存储的key
     *
     * @param url
     * @return
     */
    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            cacheKey = String.valueOf(url.hashCode());
        }

        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes) {
            String hex = Integer.toHexString(0xFF & aByte);
            if (hex.length() == 1) {
                sb.append(0);
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 获取存储的路径
     *
     * @param mContext
     * @param fileName 存储的文件名
     * @return
     */
    private File getDiskCacheDir(Context mContext, String fileName) {
        //判断sd卡是否可用
        boolean storageAvaliable = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        String cachePath;
        if (storageAvaliable) {
            ///storage/emulated/0/Android/data/package_name/cache
            cachePath = mContext.getExternalCacheDir().getPath();
        } else {
            ///data/data/package_name/cache
            cachePath = mContext.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + fileName);
    }

    /**
     * 获取存储文件所剩余的可用空间
     *
     * @param file
     * @return
     */
    private long getUsedSpace(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return file.getUsableSpace();
        }
        final StatFs statFs = new StatFs(file.getPath());
        return statFs.getBlockSizeLong() * statFs.getAvailableBlocksLong();
    }

    private static class LoaderResult {
        private ImageView imageView;
        private String uri;
        private Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }
}
