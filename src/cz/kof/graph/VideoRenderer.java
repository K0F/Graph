package cz.kof.graph;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

public class VideoRenderer {
    public static final int W = 932;
    public static final int H = 576;
    public static final int FPS = 25;

    public interface ProgressListener {
        void onProgress(int frame, int totalFrames, String status);
    }

    public static void renderMp4(
            Context ctx,
            List<Uri> imageUris,
            int[] slideOrder,
            int framesPerSlide,
            int totalFrames,
            boolean grayscale,
            String titleText,
            int titleSlots,
            Bitmap stoneBitmap,
            Typeface gomotorFont,
            File musicFile,
            File outFile,
            ProgressListener listener
    ) throws Exception {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2500000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int videoTrackIdx = -1;
        int audioTrackIdx = -1;
        boolean muxerStarted = false;

        MediaExtractor audioExtractor = null;
        if (musicFile != null && musicFile.exists()) {
            try {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(musicFile.getAbsolutePath());
                for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                    MediaFormat af = audioExtractor.getTrackFormat(i);
                    String mime = af.getString(MediaFormat.KEY_MIME);
                    if (mime != null && (mime.startsWith("audio/mp4a") || mime.equals("audio/aac"))) {
                        audioExtractor.selectTrack(i);
                        audioTrackIdx = muxer.addTrack(af);
                        break;
                    }
                }
            } catch (Exception ignored) {
                audioExtractor = null;
            }
        }

        int nSlides = slideOrder.length;
        Bitmap[] cache = new Bitmap[Math.min(imageUris.size(), 64)];
        Bitmap titleBmp = null;
        int titleFrames = (titleText != null && !titleText.trim().isEmpty() && titleSlots > 0)
                ? titleSlots * framesPerSlide : 0;

        if (titleFrames > 0) {
            titleBmp = buildTitleBitmap(stoneBitmap, titleText.trim(), gomotorFont, grayscale);
        }

        byte[] yuvBuf = new byte[W * H * 3 / 2];
        int[] argbBuf = new int[W * H];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        int curSlideIdx = -1;
        Bitmap curBmp = null;

        for (int f = 0; f <= totalFrames; f++) {
            if (f < totalFrames) {
                if (f < titleFrames && titleBmp != null) {
                    if (curBmp != titleBmp) {
                        curBmp = titleBmp;
                        argbToNV12(curBmp, argbBuf, yuvBuf);
                    }
                } else {
                    int sIdx = ((f - titleFrames) / Math.max(1, framesPerSlide)) % nSlides;
                    if (sIdx != curSlideIdx || curBmp == null || curBmp == titleBmp) {
                        curSlideIdx = sIdx;
                        int poolIdx = slideOrder[sIdx] % imageUris.size();
                        if (poolIdx < 0) poolIdx += imageUris.size();
                        if (poolIdx < cache.length && cache[poolIdx] != null) {
                            curBmp = cache[poolIdx];
                        } else {
                            curBmp = decodeAndCrop(ctx, imageUris.get(poolIdx), grayscale);
                            if (poolIdx < cache.length) cache[poolIdx] = curBmp;
                        }
                        argbToNV12(curBmp, argbBuf, yuvBuf);
                    }
                }

                int inIdx = encoder.dequeueInputBuffer(10000);
                while (inIdx < 0) {
                    drainEncoder(encoder, muxer, info, false, new int[]{videoTrackIdx}, new boolean[]{muxerStarted});
                    inIdx = encoder.dequeueInputBuffer(10000);
                }
                ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                if (inBuf != null) {
                    inBuf.clear();
                    inBuf.put(yuvBuf, 0, Math.min(inBuf.remaining(), yuvBuf.length));
                }
                long ptsUs = (f * 1000000L) / FPS;
                encoder.queueInputBuffer(inIdx, 0, yuvBuf.length, ptsUs, 0);

                if (listener != null && (f % 5 == 0 || f == totalFrames - 1)) {
                    listener.onProgress(f + 1, totalFrames, "Encoding frame " + (f + 1) + "/" + totalFrames);
                }
            } else {
                int inIdx = encoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    long ptsUs = (f * 1000000L) / FPS;
                    encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            boolean done = false;
            while (!done) {
                int outIdx = encoder.dequeueOutputBuffer(info, f == totalFrames ? 10000 : 0);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (f < totalFrames) break;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIdx = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outIdx >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(outIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxerStarted && encoded != null) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrackIdx, encoded, info);
                    }
                    encoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true;
                    }
                }
            }
        }

        if (audioExtractor != null && audioTrackIdx >= 0 && muxerStarted) {
            long maxDurationUs = (totalFrames * 1000000L) / FPS;
            ByteBuffer aBuf = ByteBuffer.allocate(65536);
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
            while (true) {
                int sz = audioExtractor.readSampleData(aBuf, 0);
                if (sz < 0) break;
                long st = audioExtractor.getSampleTime();
                if (st > maxDurationUs) break;
                aInfo.offset = 0;
                aInfo.size = sz;
                aInfo.presentationTimeUs = st;
                aInfo.flags = audioExtractor.getSampleFlags();
                muxer.writeSampleData(audioTrackIdx, aBuf, aInfo);
                audioExtractor.advance();
            }
            audioExtractor.release();
        }

        encoder.stop();
        encoder.release();
        if (muxerStarted) {
            muxer.stop();
        }
        muxer.release();
    }

    private static void drainEncoder(MediaCodec encoder, MediaMuxer muxer, MediaCodec.BufferInfo info,
                                     boolean endOfStream, int[] trackHolder, boolean[] startedHolder) {
        int outIdx = encoder.dequeueOutputBuffer(info, 2000);
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            trackHolder[0] = muxer.addTrack(encoder.getOutputFormat());
            muxer.start();
            startedHolder[0] = true;
        } else if (outIdx >= 0) {
            ByteBuffer encoded = encoder.getOutputBuffer(outIdx);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && startedHolder[0] && encoded != null) {
                encoded.position(info.offset);
                encoded.limit(info.offset + info.size);
                muxer.writeSampleData(trackHolder[0], encoded, info);
            }
            encoder.releaseOutputBuffer(outIdx, false);
        }
    }

    private static Bitmap buildTitleBitmap(Bitmap stone, String title, Typeface font, boolean grayscale) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        if (stone != null) {
            Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (grayscale) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0f);
                sp.setColorFilter(new ColorMatrixColorFilter(cm));
            }
            float scale = Math.max((float) W / stone.getWidth(), (float) H / stone.getHeight());
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate((W - stone.getWidth() * scale) * 0.5f, (H - stone.getHeight() * scale) * 0.5f);
            c.drawBitmap(stone, m, sp);
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
        if (font != null) tp.setTypeface(font);
        tp.setTextSize(64f);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float cy = H * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(title, W * 0.5f, cy, tp);
        return out;
    }

    private static Bitmap decodeAndCrop(Context ctx, Uri uri, boolean grayscale) {
        Bitmap src = null;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            src = BitmapFactory.decodeStream(is, null, opts);
        } catch (Exception ignored) {
        }
        if (src == null && "file".equals(uri.getScheme()) && uri.getPath() != null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            src = BitmapFactory.decodeFile(uri.getPath(), opts);
        }
        Bitmap dst = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(dst);
        c.drawColor(Color.BLACK);
        if (src != null) {
            float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate((W - src.getWidth() * scale) * 0.5f, (H - src.getHeight() * scale) * 0.5f);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (grayscale) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0f);
                p.setColorFilter(new ColorMatrixColorFilter(cm));
            }
            c.drawBitmap(src, m, p);
            src.recycle();
        }
        return dst;
    }

    private static void argbToNV12(Bitmap bmp, int[] argb, byte[] yuv) {
        bmp.getPixels(argb, 0, W, 0, 0, W, H);
        int frameSize = W * H;
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                int c = argb[j * W + i];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                yuv[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if ((j & 1) == 0 && (i & 1) == 0 && uvIndex + 1 < yuv.length) {
                    yuv[uvIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv[uvIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
    }
}
