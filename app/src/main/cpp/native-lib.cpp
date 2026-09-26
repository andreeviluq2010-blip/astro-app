#include <jni.h>
#include <opencv2/opencv.hpp>
#include <deque>
#include <vector>
#include <mutex>
#include <cmath>
#include <algorithm>

using namespace cv;
using namespace std;

static deque<Mat> slidingWindow;
static Mat accumulatorFrame;
static Mat prevMasterBGR;
static mutex engineMutex;

// Плавная кривая Эрмита (Smoothstep) для мягкого градиента фонаря без резких границ
inline float smoothstep(float edge0, float edge1, float x) {
    if (edge1 <= edge0) return 0.0f;
    float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Профессиональная астро-проявка: вычитание темнового тока + нелинейное усиление звезд + плавный градиент
void applyAstroGrading(Mat& bgrFrame, float starGain, int blackCut, int maskStrength) {
    // 1. Оцениваем базовый уровень темнового шума матрицы
    Scalar meanVal = mean(bgrFrame);
    float baseNoiseFloor = (float)std::min({meanVal[0], meanVal[1], meanVal[2]}) * 0.85f;
    float totalBlackCut = std::clamp(baseNoiseFloor + (float)blackCut, 0.0f, 220.0f);

    // Множитель усиления звезд (при Gain=50 -> 1.8x, при Gain=0 -> 0.5x, при Gain=100 -> 4.0x)
    float gainFactor = 0.5f + (starGain / 100.0f) * 3.5f;
    float maskNorm = std::clamp(maskStrength / 100.0f, 0.0f, 1.0f);

    int rows = bgrFrame.rows;
    int cols = bgrFrame.cols;

    // Градиент начинается с 25% высоты сверху и плавно доходит до низа кадра (75% площади экрана)
    float gradStartRow = rows * 0.25f;
    float gradEndRow = (float)rows;

    for (int y = 0; y < rows; ++y) {
        // Вычисляем плавный коэффициент приглушения фонаря для текущей строки y
        float s = smoothstep(gradStartRow, gradEndRow, (float)y);
        // Даже на 100% подавления оставляем мягкий переход и пропускаем яркие звезды
        float rowAttenuation = 1.0f - (maskNorm * 0.92f * s);

        Vec3b* ptr = bgrFrame.ptr<Vec3b>(y);
        for (int x = 0; x < cols; ++x) {
            for (int c = 0; c < 3; ++c) {
                float val = (float)ptr[x][c];
                // Отсекаем темновой шум (на коврике в темноте здесь станет ровно 0!)
                float clean = std::max(0.0f, val - totalBlackCut);
                // Мягкое нелинейное вытягивание звезд без пересвета фона
                float floatnorm = clean / (255.0f - totalBlackCut + 1.0f);
                float boosted = std::pow(floatnorm, 0.88f) * 255.0f * gainFactor;
                // Применяем плавный градиент подавления фонаря соседа
                float finalVal = boosted * rowAttenuation;
                ptr[x][c] = saturate_cast<uchar>(finalVal);
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_astro_hyperlapse_AstroCameraService_nativeInitEngine(JNIEnv* env, jobject thiz) {
    lock_guard<mutex> lock(engineMutex);
    slidingWindow.clear();
    accumulatorFrame.release();
    prevMasterBGR.release();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_astro_hyperlapse_AstroCameraService_nativePushSubframe(
        JNIEnv* env, jobject thiz,
        jbyteArray yuvData, jint width, jint height,
        jint windowSize, jint mode, jint maskPercent, jfloat starGain, jint blackCut) {

    lock_guard<mutex> lock(engineMutex);
    jbyte* yuvPtr = env->GetByteArrayElements(yuvData, nullptr);
    Mat yuvMat(height + height / 2, width, CV_8UC1, (uchar*)yuvPtr);
    Mat bgrFrame;
    cvtColor(yuvMat, bgrFrame, COLOR_YUV2BGR_NV21);
    env->ReleaseByteArrayElements(yuvData, yuvPtr, JNI_ABORT);

    Mat resizedBGR;
    resize(bgrFrame, resizedBGR, Size(3840, 2160), 0, 0, INTER_LINEAR);

    // Проявляем кадр с отсечением темнового шума и мягким градиентом
    applyAstroGrading(resizedBGR, starGain, blackCut, maskPercent);

    Mat floatFrame;
    resizedBGR.convertTo(floatFrame, CV_32FC3);

    if (accumulatorFrame.empty()) {
        accumulatorFrame = floatFrame.clone();
        slidingWindow.push_back(floatFrame);
    } else {
        accumulatorFrame += floatFrame;
        slidingWindow.push_back(floatFrame);
        if ((int)slidingWindow.size() > windowSize) {
            accumulatorFrame -= slidingWindow.front();
            slidingWindow.pop_front();
        }
    }

    int minReady = std::min(4, windowSize);
    if (mode == 0) {
        return (int)slidingWindow.size() >= minReady;
    } else {
        return (int)slidingWindow.size() >= windowSize;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_astro_hyperlapse_AstroCameraService_nativeClearWindowForStepMode(JNIEnv* env, jobject thiz) {
    lock_guard<mutex> lock(engineMutex);
    slidingWindow.clear();
    accumulatorFrame.release();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_astro_hyperlapse_AstroCameraService_nativeGenerateMasterAndInterpolate(
        JNIEnv* env, jobject thiz, jint dlssMultiplier, jobject surfaceCallback) {

    lock_guard<mutex> lock(engineMutex);
    if (slidingWindow.empty() || accumulatorFrame.empty()) return 0;

    Mat currentMasterFloat = accumulatorFrame / (float)slidingWindow.size();
    Mat currentMasterBGR;
    currentMasterFloat.convertTo(currentMasterBGR, CV_8UC3);

    jclass cls = env->GetObjectClass(surfaceCallback);
    jmethodID mid = env->GetMethodID(cls, "onEncodedFrameReady", "([BII)V");

    int generatedCount = 0;
    int mult = std::max(1, (int)dlssMultiplier);

    if (!prevMasterBGR.empty() && mult > 1) {
        for (int i = 1; i < mult; ++i) {
            double alpha = (double)i / (double)mult;
            Mat interpBGR;
            addWeighted(prevMasterBGR, 1.0 - alpha, currentMasterBGR, alpha, 0.0, interpBGR);

            Mat interpYUV;
            cvtColor(interpBGR, interpYUV, COLOR_BGR2YUV_I420);
            int byteSize = (int)(interpYUV.total() * interpYUV.elemSize());
            jbyteArray outArr = env->NewByteArray(byteSize);
            env->SetByteArrayRegion(outArr, 0, byteSize, (const jbyte*)interpYUV.data);
            env->CallVoidMethod(surfaceCallback, mid, outArr, 3840, 2160);
            env->DeleteLocalRef(outArr);
            generatedCount++;
        }
    }

    Mat masterYUV;
    cvtColor(currentMasterBGR, masterYUV, COLOR_BGR2YUV_I420);
    int byteSize = (int)(masterYUV.total() * masterYUV.elemSize());
    jbyteArray outArr = env->NewByteArray(byteSize);
    env->SetByteArrayRegion(outArr, 0, byteSize, (const jbyte*)masterYUV.data);
    env->CallVoidMethod(surfaceCallback, mid, outArr, 3840, 2160);
    env->DeleteLocalRef(outArr);
    generatedCount++;

    prevMasterBGR = currentMasterBGR.clone();
    return generatedCount;
}
