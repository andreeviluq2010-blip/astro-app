#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <opencv2/video.hpp>
#include <vector>
#include <cmath>
#include <mutex>
#include <algorithm>

using namespace cv;
using namespace std;

class AstroProcessor {
private:
    Mat skyAcc32F;       // 32-bit аккумулятор неба (с перепроекцией звезд)
    Mat skyMax32F;       // Пиковый буфер для ярких ядер звезд
    Mat groundAcc32F;    // Статичный аккумулятор земли (без размытия крыш/деревьев)
    Mat prevSkyGray;     // Предыдущий кадр неба для быстрого трекинга
    vector<Point2f> prevStars;

    int frameCount = 0;
    int targetStack = 16;
    float skyLinePct;    // Верхняя линия (выше нее 100% буст звезд)
    float groundLinePct; // Нижняя линия (ниже нее щит от фонаря соседа)
    float stretchFactor;
    float groundSuppression;
    bool isZenithMode;
    bool isRollingMode;
    mutex processMutex;

    void detectStars(const Mat& gray, vector<Point2f>& stars) {
        Mat starMask;
        threshold(gray, starMask, 235, 255, THRESH_BINARY_INV); // Игнорируем пересвеченные фонари
        goodFeaturesToTrack(gray, stars, 700, 0.03, 6.0, starMask, 3, false, 0.04);
    }

    // Плавная S-кривая (smoothstep) для бесшовного перехода между линией неба и линией фонаря
    inline float smoothStep(float edge0, float edge1, float x) {
        float t = std::clamp((x - edge0) / (edge1 - edge0 + 1e-5f), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

public:
    AstroProcessor(float skyPct, float gndPct, float stretch, float gndSupp, bool zenith, bool rolling, int stackN)
        : skyLinePct(skyPct),
          groundLinePct(std::max(skyPct + 2.0f, gndPct)),
          stretchFactor(stretch),
          groundSuppression(gndSupp),
          isZenithMode(zenith),
          isRollingMode(rolling),
          targetStack(std::max(2, stackN)),
          frameCount(0) {}

    void updateLiveParams(float skyPct, float gndPct, float stretch, float gndSupp, bool zenith) {
        lock_guard<mutex> lock(processMutex);
        skyLinePct = skyPct;
        groundLinePct = std::max(skyPct + 2.0f, gndPct);
        stretchFactor = stretch;
        groundSuppression = gndSupp;
        isZenithMode = zenith;
    }

    // Возвращает true, если готов новый Мастер-кадр
    bool processFrame(Mat& currentFrame) {
        lock_guard<mutex> lock(processMutex);
        if (currentFrame.empty()) return false;

        int rows = currentFrame.rows;
        int cols = currentFrame.cols;
        int skyCutY = isZenithMode ? rows : std::clamp(static_cast<int>(rows * (groundLinePct / 100.0f)), 100, rows);

        Rect skyRect(0, 0, cols, skyCutY);
        Mat currSky = currentFrame(skyRect);
        Mat currSkyGray;
        cvtColor(currSky, currSkyGray, COLOR_RGBA2GRAY);

        Mat curr32F;
        currentFrame.convertTo(curr32F, CV_32FC4);

        if (frameCount == 0) {
            skyAcc32F = curr32F.clone();
            skyMax32F = curr32F.clone();
            groundAcc32F = curr32F.clone();
            prevSkyGray = currSkyGray.clone();
            detectStars(prevSkyGray, prevStars);
            frameCount = 1;
            return false;
        }

        // Вычисляем поворот звезд между прошлым и текущим кадром (в 16 раз быстрее пачки!)
        Mat transform;
        if (prevStars.size() >= 8) {
            vector<Point2f> currStars;
            vector<uchar> status;
            vector<float> err;
            calcOpticalFlowPyrLK(prevSkyGray, currSkyGray, prevStars, currStars, status, err, Size(21, 21), 3);

            vector<Point2f> goodPrev, goodCurr;
            for (size_t i = 0; i < status.size(); i++) {
                if (status[i] && err[i] < 12.0f) {
                    goodPrev.push_back(prevStars[i]);
                    goodCurr.push_back(currStars[i]);
                }
            }
            if (goodPrev.size() >= 6) {
                // Поворачиваем прошлый аккумулятор к новой позиции звезд
                transform = estimateAffinePartial2D(goodPrev, goodCurr, noArray(), RANSAC, 2.5);
            }
        }

        if (!transform.empty()) {
            Mat warpedAccSky, warpedMaxSky;
            warpAffine(skyAcc32F(skyRect), warpedAccSky, transform, skyRect.size(), INTER_LINEAR, BORDER_REPLICATE);
            warpAffine(skyMax32F(skyRect), warpedMaxSky, transform, skyRect.size(), INTER_LINEAR, BORDER_REPLICATE);
            warpedAccSky.copyTo(skyAcc32F(skyRect));
            warpedMaxSky.copyTo(skyMax32F(skyRect));
        }

        frameCount++;

        if (isRollingMode) {
            // Экспоненциальное скользящее окно (IIR): эквивалент N кадров без затрат 3 ГБ RAM
            float alpha = 1.0f / static_cast<float>(std::min(frameCount, targetStack));
            addWeighted(skyAcc32F, 1.0f - alpha, curr32F, alpha, 0.0, skyAcc32F);
            skyMax32F = max(skyMax32F * (1.0f - alpha * 0.35f), curr32F);
            addWeighted(groundAcc32F, 1.0f - alpha, curr32F, alpha, 0.0, groundAcc32F);
        } else {
            // Пакетный режим
            float alpha = 1.0f / static_cast<float>(frameCount);
            addWeighted(skyAcc32F, 1.0f - alpha, curr32F, alpha, 0.0, skyAcc32F);
            skyMax32F = max(skyMax32F, curr32F);
            addWeighted(groundAcc32F, 1.0f - alpha, curr32F, alpha, 0.0, groundAcc32F);
        }

        prevSkyGray = currSkyGray.clone();
        if (frameCount % 3 == 0 || prevStars.size() < 30) {
            detectStars(prevSkyGray, prevStars);
        }

        if (isRollingMode) {
            // В скользящем режиме выдаем новый Мастер-кадр на каждый подкадр после стартового разгона (4 кадра)
            return (frameCount >= std::min(4, targetStack));
        } else {
            return (frameCount >= targetStack);
        }
    }

    void finalizeMasterFrame(Mat& outputFrame) {
        lock_guard<mutex> lock(processMutex);
        if (skyAcc32F.empty() || frameCount == 0) return;

        int rows = skyAcc32F.rows;
        int cols = skyAcc32F.cols;

        float yTop = isZenithMode ? rows : rows * (skyLinePct / 100.0f);
        float yBot = isZenithMode ? rows : rows * (groundLinePct / 100.0f);

        Mat skyBlend32F = skyAcc32F * 0.78f + skyMax32F * 0.22f;
        Mat sky8U, gnd8U;
        skyBlend32F.convertTo(sky8U, CV_8UC4);
        groundAcc32F.convertTo(gnd8U, CV_8UC4);

        // Вычитание купола засветки от фонаря соседа и боковой виньетки
        Mat skySmall, skyBg;
        resize(sky8U, skySmall, Size(cols / 32, rows / 32), 0, 0, INTER_AREA);
        GaussianBlur(skySmall, skySmall, Size(9, 9), 0);
        resize(skySmall, skyBg, sky8U.size(), 0, 0, INTER_LINEAR);

        float factor = std::max(10.0f, stretchFactor);
        float denom = asinh(factor);
        float keepGnd = 1.0f - std::clamp(groundSuppression, 0.0f, 0.96f);

        #pragma omp parallel for
        for (int y = 0; y < rows; y++) {
            const Vec4b* sRow = sky8U.ptr<Vec4b>(y);
            const Vec4b* bgRow = skyBg.ptr<Vec4b>(y);
            const Vec4b* gRow = gnd8U.ptr<Vec4b>(y);
            Vec4b* outRow = outputFrame.ptr<Vec4b>(y);

            // Вес неба: 1.0 выше зеленой линии, плавный спад в буфере, 0.0 ниже красной линии фонаря
            float skyW = isZenithMode ? 1.0f : (1.0f - smoothStep(yTop, yBot, static_cast<float>(y)));

            for (int x = 0; x < cols; x++) {
                for (int c = 0; c < 3; c++) {
                    // 1. Зона Усиления Звезд (верх)
                    float localBg = bgRow[x][c] * 0.74f;
                    float vSky = std::max(0.0f, (sRow[x][c] - localBg)) / (255.0f - localBg + 1e-5f);
                    float stretched = asinh(vSky * factor) / denom;
                    // Защита от пересвета ярких звезд
                    stretched = stretched / (1.0f + stretched * 0.12f) * 1.12f;
                    float skyVal = std::clamp(stretched * 255.0f, 0.0f, 255.0f);

                    // 2. Зона Затемнения Фонаря Соседа (низ, Reinhard компрессор)
                    float vGnd = gRow[x][c] / 255.0f;
                    float compressedGnd = (vGnd * keepGnd) / (1.0f + vGnd * (1.0f + groundSuppression * 5.0f));
                    float gndVal = std::clamp(compressedGnd * 255.0f, 0.0f, 255.0f);

                    outRow[x][c] = saturate_cast<uchar>(skyVal * skyW + gndVal * (1.0f - skyW));
                }
                outRow[x][3] = 255;
            }
        }
    }

    void resetBatch() {
        lock_guard<mutex> lock(processMutex);
        if (!isRollingMode) {
            skyAcc32F.release();
            skyMax32F.release();
            groundAcc32F.release();
            prevSkyGray.release();
            prevStars.clear();
            frameCount = 0;
        }
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_initProcessor(
        JNIEnv *env, jobject thiz,
        jfloat skyLinePct, jfloat groundLinePct,
        jfloat stretchFactor, jfloat groundSuppression,
        jboolean isZenithMode, jboolean isRollingMode, jint stackTarget) {
    return reinterpret_cast<jlong>(new AstroProcessor(
            skyLinePct, groundLinePct, stretchFactor, groundSuppression, isZenithMode, isRollingMode, stackTarget));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_updateLiveParams(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloat skyLinePct, jfloat groundLinePct,
        jfloat stretchFactor, jfloat groundSuppression, jboolean isZenithMode) {
    auto* processor = reinterpret_cast<AstroProcessor*>(handle);
    if (processor) {
        processor->updateLiveParams(skyLinePct, groundLinePct, stretchFactor, groundSuppression, isZenithMode);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_addFrame(JNIEnv *env, jobject thiz, jlong handle, jobject bitmap) {
    auto* processor = reinterpret_cast<AstroProcessor*>(handle);
    if (!processor) return JNI_FALSE;
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;
    Mat currentFrame(info.height, info.width, CV_8UC4, pixels);
    bool ready = processor->processFrame(currentFrame);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_finalizeMasterFrame(JNIEnv *env, jobject thiz, jlong handle, jobject outBitmap) {
    auto* processor = reinterpret_cast<AstroProcessor*>(handle);
    if (!processor) return;
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, outBitmap, &info) < 0) return;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) < 0) return;
    Mat outFrame(info.height, info.width, CV_8UC4, pixels);
    processor->finalizeMasterFrame(outFrame);
    AndroidBitmap_unlockPixels(env, outBitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_resetBatch(JNIEnv *env, jobject thiz, jlong handle) {
    auto* processor = reinterpret_cast<AstroProcessor*>(handle);
    if (processor) processor->resetBatch();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_releaseProcessor(JNIEnv *env, jobject thiz, jlong handle) {
    auto* processor = reinterpret_cast<AstroProcessor*>(handle);
    delete processor;
}

// --- ОПТИМИЗИРОВАННЫЙ В 16 РАЗ АСТРО-DLSS (Пирамидальный Optical Flow 1/4 -> 4K) ---
extern "C" JNIEXPORT void JNICALL
Java_com_example_astrohyperlapse_NativeAstroProcessor_generateIntermediateFrames(
        JNIEnv *env, jobject thiz, jobject bmp1, jobject bmp2, jint multiplier, jobjectArray outBitmaps) {
    AndroidBitmapInfo info;
    void *pixels1, *pixels2;
    AndroidBitmap_getInfo(env, bmp1, &info);
    AndroidBitmap_lockPixels(env, bmp1, &pixels1);
    AndroidBitmap_lockPixels(env, bmp2, &pixels2);

    int W = info.width;
    int H = info.height;
    Mat frame1(H, W, CV_8UC4, pixels1);
    Mat frame2(H, W, CV_8UC4, pixels2);

    // Считаем оптический поток в 1/4 разрешения (в 16 раз быстрее и холоднее!)
    int sW = W / 4;
    int sH = H / 4;
    Mat small1, small2, gray1, gray2;
    resize(frame1, small1, Size(sW, sH), 0, 0, INTER_AREA);
    resize(frame2, small2, Size(sW, sH), 0, 0, INTER_AREA);
    cvtColor(small1, gray1, COLOR_RGBA2GRAY);
    cvtColor(small2, gray2, COLOR_RGBA2GRAY);

    Ptr<DISOpticalFlow> dis = DISOpticalFlow::create(DISOpticalFlow::PRESET_FAST);
    Mat flowFwdSmall, flowBwdSmall, flowForward, flowBackward;
    dis->calc(gray1, gray2, flowFwdSmall);
    dis->calc(gray2, gray1, flowBwdSmall);

    // Масштабируем векторное поле обратно до 4K и умножаем длину векторов на 4.0
    resize(flowFwdSmall, flowForward, Size(W, H), 0, 0, INTER_LINEAR);
    resize(flowBwdSmall, flowBackward, Size(W, H), 0, 0, INTER_LINEAR);
    flowForward *= 4.0f;
    flowBackward *= 4.0f;

    for (int i = 1; i < multiplier; i++) {
        float t = static_cast<float>(i) / multiplier;
        jobject outBmp = env->GetObjectArrayElement(outBitmaps, i - 1);
        void* outPixels;
        AndroidBitmap_lockPixels(env, outBmp, &outPixels);
        Mat outFrame(H, W, CV_8UC4, outPixels);

        Mat map1X(H, W, CV_32FC1), map1Y(H, W, CV_32FC1);
        Mat map2X(H, W, CV_32FC1), map2Y(H, W, CV_32FC1);

        #pragma omp parallel for
        for (int y = 0; y < H; y++) {
            const Point2f* fwdRow = flowForward.ptr<Point2f>(y);
            const Point2f* bwdRow = flowBackward.ptr<Point2f>(y);
            float* m1x = map1X.ptr<float>(y);
            float* m1y = map1Y.ptr<float>(y);
            float* m2x = map2X.ptr<float>(y);
            float* m2y = map2Y.ptr<float>(y);
            for (int x = 0; x < W; x++) {
                m1x[x] = x - t * fwdRow[x].x;
                m1y[x] = y - t * fwdRow[x].y;
                m2x[x] = x - (1.0f - t) * bwdRow[x].x;
                m2y[x] = y - (1.0f - t) * bwdRow[x].y;
            }
        }

        Mat warped1, warped2;
        remap(frame1, warped1, map1X, map1Y, INTER_LINEAR);
        remap(frame2, warped2, map2X, map2Y, INTER_LINEAR);
        addWeighted(warped1, 1.0 - t, warped2, t, 0.0, outFrame);

        AndroidBitmap_unlockPixels(env, outBmp);
        env->DeleteLocalRef(outBmp);
    }

    AndroidBitmap_unlockPixels(env, bmp1);
    AndroidBitmap_unlockPixels(env, bmp2);
}
