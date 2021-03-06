/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.core.provider;

import static androidx.core.provider.FontsContractCompat.FontFamilyResult.STATUS_OK;
import static androidx.core.provider.FontsContractCompat.FontFamilyResult.STATUS_UNEXPECTED_DATA_PROVIDED;
import static androidx.core.provider.FontsContractCompat.FontFamilyResult.STATUS_WRONG_CERTIFICATES;
import static androidx.core.provider.FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR;
import static androidx.core.provider.FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND;
import static androidx.core.provider.FontsContractCompat.FontRequestCallback.FAIL_REASON_PROVIDER_NOT_FOUND;
import static androidx.core.provider.FontsContractCompat.FontRequestCallback.FAIL_REASON_WRONG_CERTIFICATES;
import static androidx.core.provider.FontsContractCompat.FontRequestCallback.RESULT_SUCCESS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Process;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;
import androidx.collection.SimpleArrayMap;
import androidx.core.content.res.FontResourcesParserCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.TypefaceCompat;
import androidx.core.provider.FontRequestThreadPool.ReplyCallback;

import java.util.ArrayList;
import java.util.concurrent.Callable;

class FontRequestWorker {

    private FontRequestWorker() {}

    static final LruCache<String, Typeface> sTypefaceCache = new LruCache<>(16);

    private static final FontRequestThreadPool BACKGROUND_THREAD = new FontRequestThreadPool(
            "fonts-androidx",
            Process.THREAD_PRIORITY_BACKGROUND,
            10000 /* keepAliveTime */
    );

    /** Package protected to prevent synthetic accessor */
    static final Object LOCK = new Object();

    /** Package protected to prevent synthetic accessor */
    @GuardedBy("LOCK")
    static final SimpleArrayMap<String, ArrayList<ReplyCallback<TypefaceResult>>> PENDING_REPLIES =
            new SimpleArrayMap<>();

    static void resetTypefaceCache() {
        sTypefaceCache.evictAll();
    }

    /**
     * Internal method of requestFont for avoiding holding strong refernece of Context.
     */
    @SuppressWarnings("deprecation")
    static void requestFontInternal(
            final @NonNull Context appContext,
            final @NonNull FontRequest request,
            final @NonNull FontsContractCompat.FontRequestCallback callback,
            final @NonNull Handler handler
    ) {
        final Handler callerHandler = CalleeHandler.create();
        final int defaultStyle = Typeface.NORMAL;

        final String id = createRequestId(request, defaultStyle);
        Typeface cached = sTypefaceCache.get(id);
        if (cached != null) {
            notifyRetrieved(callerHandler, callback, cached);
        }

        handler.post(new Runnable() {
            @Override
            public void run() {
                FontsContractCompat.FontFamilyResult result;
                try {
                    result = FontProvider.getFontFamilyResult(appContext, request, null);
                } catch (PackageManager.NameNotFoundException e) {
                    notifyFailed(callerHandler, callback, FAIL_REASON_PROVIDER_NOT_FOUND);
                    return;
                }

                if (result.getStatusCode() != STATUS_OK) {
                    switch (result.getStatusCode()) {
                        case STATUS_WRONG_CERTIFICATES:
                            notifyFailed(callerHandler, callback, FAIL_REASON_WRONG_CERTIFICATES);
                            return;
                        case STATUS_UNEXPECTED_DATA_PROVIDED:
                            notifyFailed(callerHandler, callback, FAIL_REASON_FONT_LOAD_ERROR);
                            return;
                        default:
                            // fetchFont returns unexpected status type. Fallback to load error.
                            notifyFailed(callerHandler, callback, FAIL_REASON_FONT_LOAD_ERROR);
                            return;
                    }
                }

                final FontsContractCompat.FontInfo[] fonts = result.getFonts();
                if (fonts == null || fonts.length == 0) {
                    notifyFailed(callerHandler, callback, FAIL_REASON_FONT_NOT_FOUND);
                    return;
                }
                for (final FontsContractCompat.FontInfo font : fonts) {
                    if (font.getResultCode() != FontsContractCompat.Columns.RESULT_CODE_OK) {
                        // We proceed if all font entry is ready to use. Otherwise report the first
                        // error.
                        final int resultCode = font.getResultCode();
                        if (resultCode < 0) {
                            // Negative values are reserved for internal errors. Fallback to load
                            // error.
                            notifyFailed(callerHandler, callback, FAIL_REASON_FONT_LOAD_ERROR);
                        } else {
                            notifyFailed(callerHandler, callback, resultCode);
                        }
                        return;
                    }
                }

                final Typeface typeface = TypefaceCompat.createFromFontInfo(appContext,
                        null /* cancellationSignal */,
                        fonts,
                        defaultStyle
                );

                if (typeface == null) {
                    // Something went wrong during reading font files. This happens if the given
                    // font file is an unsupported font type.
                    notifyFailed(callerHandler, callback, FAIL_REASON_FONT_LOAD_ERROR);
                    return;
                }

                if (typeface != null) {
                    sTypefaceCache.put(id, typeface);
                }
                notifyRetrieved(callerHandler, callback, typeface);
            }
        });
    }

    static void notifyFailed(
            @NonNull final Handler callerThreadHandler,
            @NonNull final FontsContractCompat.FontRequestCallback callback,
            final int code
    ) {
        callerThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onTypefaceRequestFailed(code);
            }
        });
    }

    static void notifyRetrieved(
            @NonNull final Handler callerThreadHandler,
            @NonNull final FontsContractCompat.FontRequestCallback callback,
            @NonNull final Typeface typeface
    ) {
        callerThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onTypefaceRetrieved(typeface);
            }
        });
    }

    /**
     * Used by TypefaceCompat and tests.
     *
     * @param context         Context
     * @param request         FontRequest that defines the font to be loaded.
     * @param fontCallback    the callback to be called for async loading
     * @param handler         the Handler that the callback will be called on.
     * @param isBlockingFetch when boolean the call will be synchronous
     * @return Typeface the resulting Typeface if it is not an asynch request.
     */
    static Typeface getTypeface(
            @NonNull final Context context,
            @NonNull final FontRequest request,
            @Nullable final ResourcesCompat.FontCallback fontCallback,
            @Nullable final Handler handler, boolean isBlockingFetch, int timeout,
            final int style) {
        final String id = createRequestId(request, style);
        Typeface cached = sTypefaceCache.get(id);
        if (cached != null) {
            if (fontCallback != null) {
                fontCallback.onFontRetrieved(cached);
            }
            return cached;
        }

        // when timeout is infinite, do not post to bg thread, since it will block other requests
        if (isBlockingFetch && timeout == FontResourcesParserCompat.INFINITE_TIMEOUT_VALUE) {
            // Wait forever. No need to post to the thread.
            TypefaceResult typefaceResult = getFontInternal(id, context, request, style);
            Typeface typeface = typefaceResult.mTypeface;
            if (fontCallback != null) {
                if (typefaceResult.mResult == STATUS_OK) {
                    fontCallback.callbackSuccessAsync(typeface, handler);
                } else {
                    fontCallback.callbackFailAsync(typefaceResult.mResult, handler);
                }
            }
            return typeface;
        }

        final Callable<TypefaceResult> fetcher = new Callable<TypefaceResult>() {
            @Override
            public TypefaceResult call() {
                TypefaceResult typeface = getFontInternal(id, context, request, style);
                return typeface;
            }
        };

        if (isBlockingFetch) {
            try {
                return BACKGROUND_THREAD.postAndWait(fetcher, timeout).mTypeface;
            } catch (InterruptedException e) {
                return null;
            }
        } else {
            final ReplyCallback<TypefaceResult> reply = fontCallback == null ? null :
                    new ReplyCallback<TypefaceResult>() {
                @Override
                public void onReply(final TypefaceResult typeface) {
                    if (typeface == null) {
                        fontCallback.callbackFailAsync(
                                FAIL_REASON_FONT_NOT_FOUND, handler);
                    } else if (typeface.mResult == STATUS_OK) {
                        fontCallback.callbackSuccessAsync(typeface.mTypeface, handler);
                    } else {
                        fontCallback.callbackFailAsync(typeface.mResult, handler);
                    }
                }
            };

            synchronized (LOCK) {
                ArrayList<ReplyCallback<TypefaceResult>> pendingReplies = PENDING_REPLIES.get(id);
                if (pendingReplies != null) {
                    // Already requested. Do not request the same provider again and insert the
                    // reply to the queue instead.
                    if (reply != null) {
                        pendingReplies.add(reply);
                    }
                    return null;
                }
                if (reply != null) {
                    pendingReplies = new ArrayList<>();
                    pendingReplies.add(reply);
                    PENDING_REPLIES.put(id, pendingReplies);
                }
            }
            BACKGROUND_THREAD.postAndReply(fetcher, new ReplyCallback<TypefaceResult>() {
                @Override
                public void onReply(final TypefaceResult typeface) {
                    final ArrayList<ReplyCallback<TypefaceResult>> replies;
                    synchronized (LOCK) {
                        replies = PENDING_REPLIES.get(id);
                        if (replies == null) {
                            return;  // Nobody requested replies. Do nothing.
                        }
                        PENDING_REPLIES.remove(id);
                    }
                    for (int i = 0; i < replies.size(); ++i) {
                        replies.get(i).onReply(typeface);
                    }
                }
            });
            return null;
        }
    }

    private static String createRequestId(@NonNull FontRequest request, int style) {
        return request.getId() + "-" + style;
    }

    /** Package protected to prevent synthetic accessor */
    @SuppressLint("WrongConstant")
    @NonNull
    static TypefaceResult getFontInternal(
            @NonNull final String cacheId,
            @NonNull final Context context,
            @NonNull final FontRequest request,
            int style
    ) {
        FontsContractCompat.FontFamilyResult result;
        try {
            result = FontProvider.getFontFamilyResult(context, request, null);
        } catch (PackageManager.NameNotFoundException e) {
            return new TypefaceResult(null, FAIL_REASON_PROVIDER_NOT_FOUND);
        }
        if (result.getStatusCode() == STATUS_OK) {
            final Typeface typeface = TypefaceCompat.createFromFontInfo(
                    context, null /* CancellationSignal */, result.getFonts(), style);
            if (typeface != null) {
                sTypefaceCache.put(cacheId, typeface);
            }

            return new TypefaceResult(typeface, typeface != null
                    ? RESULT_SUCCESS
                    : FAIL_REASON_FONT_LOAD_ERROR);
        }
        int resultCode = result.getStatusCode() == STATUS_WRONG_CERTIFICATES
                ? FAIL_REASON_WRONG_CERTIFICATES
                : FAIL_REASON_FONT_LOAD_ERROR;

        return new TypefaceResult(null, resultCode);
    }

    private static final class TypefaceResult {
        final Typeface mTypeface;
        @FontsContractCompat.FontRequestCallback.FontRequestFailReason final int mResult;

        TypefaceResult(@Nullable Typeface typeface,
                @FontsContractCompat.FontRequestCallback.FontRequestFailReason int result) {
            mTypeface = typeface;
            mResult = result;
        }
    }

}
