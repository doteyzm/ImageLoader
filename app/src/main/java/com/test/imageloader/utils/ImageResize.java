package com.test.imageloader.utils;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

/**
 * 图片的压缩功能
 */
public class ImageResize {
    private final static String TAG = "ImageResizer";

    public ImageResize() {
    }

    /**
     * 从资源图片中获取bitmap
     *
     * @param res
     * @param resId     资源图片的id
     * @param reqWidth  目标宽
     * @param reqHeight 目标高
     * @return
     */
    public Bitmap decodeSampledBitmapFromResource(Resources res, int resId, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();

        //将inJustDecodeBounds设置为true，获取图片的原始宽高信息
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);
        //获取裁剪压缩的比例
        options.inSampleSize = calculateBitmapInSampleSize(options, reqWidth, reqHeight);
        //将inJustDecodeBounds设置为false，获取裁剪后的图片
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }

    /**
     * 利用FileDescriptor中获取Bitmap
     *
     * @param fd
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);
        options.inSampleSize = calculateBitmapInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);
    }

    /**
     * 计算裁剪的比例
     */
    private int calculateBitmapInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        if (reqWidth == 0 || reqHeight == 0) {
            return 1;
        }
        int originWidth = options.outWidth;//图片原始宽度
        int originHeight = options.outHeight;//图片原始高度
        int sampleSize = 1;
        if (originWidth > reqWidth || originHeight > reqHeight) {
            int halfWidth = originWidth / 2;
            int halfHeight = originHeight / 2;
            while ((halfWidth / sampleSize) >= reqWidth && (halfHeight / sampleSize) >= reqHeight) {
                sampleSize *= 2;
            }
        }
        return sampleSize;
    }
}
