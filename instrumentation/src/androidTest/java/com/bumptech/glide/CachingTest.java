package com.bumptech.glide;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.test.BitmapSubject;
import com.bumptech.glide.test.GlideApp;
import com.bumptech.glide.test.ResourceIds;
import com.bumptech.glide.test.TearDownGlide;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests various aspects of memory and disk caching to verify resources can be retrieved as we
 * expect.
 */
@RunWith(AndroidJUnit4.class)
public class CachingTest {
  private static final int IMAGE_SIZE_PIXELS = 500;
  private static final long TIMEOUT_MS = 5000;
  private static final TimeUnit TIMEOUT_UNIT = TimeUnit.MILLISECONDS;
  // Store at least 10 500x500 pixel Bitmaps with the ARGB_8888 config to be safe.
  private static final long CACHE_SIZE_BYTES =
      IMAGE_SIZE_PIXELS * IMAGE_SIZE_PIXELS * 4 * 10;

  @Rule public TearDownGlide tearDownGlide = new TearDownGlide();
  @Mock private RequestListener<Drawable> requestListener;

  private Context context;

  @Before
  public void setUp() throws InterruptedException {
    MockitoAnnotations.initMocks(this);
    context = InstrumentationRegistry.getTargetContext();

    Glide.init(
        context, new GlideBuilder().setMemoryCache(new LruResourceCache(CACHE_SIZE_BYTES)));
  }

  @Test
  public void submit_withPreviousRequestClearedFromMemory_completesFromDataDiskCache()
      throws InterruptedException, ExecutionException, TimeoutException {
    FutureTarget<Drawable> future = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    GlideApp.with(context).clear(future);

    clearMemoryCacheOnMainThread();

    GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .listener(requestListener)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS)
        .get(TIMEOUT_MS, TIMEOUT_UNIT);

    verify(requestListener)
        .onResourceReady(
            any(Drawable.class),
            any(),
            anyTarget(),
            eq(DataSource.DATA_DISK_CACHE),
            anyBoolean());
  }

  @Test
  public void submit_withPreviousButNoLongerReferencedIdenticalRequest_completesFromMemoryCache()
      throws InterruptedException, TimeoutException, ExecutionException {
    // We can't allow any mocks (RequestListner, Target etc) to reference this request or the test
    // will fail due to the transient strong reference to the request.
    GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS)
        .get(TIMEOUT_MS, TIMEOUT_UNIT);
    // Force the collection of weak references now that the listener/request in the first load is no
    // longer referenced.
    Runtime.getRuntime().gc();
    GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .listener(requestListener)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS)
        .get(TIMEOUT_MS, TIMEOUT_UNIT);

    verify(requestListener).onResourceReady(
        any(Drawable.class), any(), anyTarget(), eq(DataSource.MEMORY_CACHE), anyBoolean());
  }

  @Test
  public void submit_withPreviousButNoLongerReferencedIdenticalRequest_doesNotRecycleBitmap()
      throws InterruptedException, TimeoutException, ExecutionException {
    // We can't allow any mocks (RequestListener, Target etc) to reference this request or the test
    // will fail due to the transient strong reference to the request.
    Bitmap bitmap = GlideApp.with(context)
        .asBitmap()
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS)
        .get(TIMEOUT_MS, TIMEOUT_UNIT);
    // Force the collection of weak references now that the listener/request in the first load is no
    // longer referenced.
    Runtime.getRuntime().gc();

    FutureTarget<Bitmap> future = GlideApp.with(context)
        .asBitmap()
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    future.get(TIMEOUT_MS, TIMEOUT_UNIT);
    Glide.with(context).clear(future);

    clearMemoryCacheOnMainThread();

    BitmapSubject.assertThat(bitmap).isNotRecycled();
  }

  @Test
  public void clearDiskCache_doesNotPreventFutureLoads()
      throws ExecutionException, InterruptedException, TimeoutException {
    FutureTarget<Drawable> future = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    future.get(TIMEOUT_MS, TIMEOUT_UNIT);
    GlideApp.with(context).clear(future);

    clearMemoryCacheOnMainThread();
    GlideApp.get(context).clearDiskCache();

    future = GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS);
    future.get(TIMEOUT_MS, TIMEOUT_UNIT);

    GlideApp.with(context).clear(future);
    clearMemoryCacheOnMainThread();

    GlideApp.with(context)
        .load(ResourceIds.raw.canonical)
        .listener(requestListener)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .submit(IMAGE_SIZE_PIXELS, IMAGE_SIZE_PIXELS)
        .get(TIMEOUT_MS, TIMEOUT_UNIT);

    verify(requestListener).onResourceReady(
        any(Drawable.class), any(), anyTarget(), eq(DataSource.DATA_DISK_CACHE), anyBoolean());
  }

  @SuppressWarnings("unchecked")
  private static Target<Drawable> anyTarget() {
    return (Target<Drawable>) any(Target.class);
  }

  private void clearMemoryCacheOnMainThread() throws InterruptedException {
    final CountDownLatch countDownLatch = new CountDownLatch(1);
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        Glide.get(context).clearMemory();
        countDownLatch.countDown();
      }
    });
    countDownLatch.await(TIMEOUT_MS, TIMEOUT_UNIT);
  }
}
